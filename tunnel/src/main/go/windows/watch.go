//go:build windows

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"log"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"net.paramnezia/tunnel/linkwatch"
)

// supervisor watches the running tunnel and repairs it, inside the service, so the tunnel is looked
// after even while the app is closed or has crashed (it used to be the app's job). The rules are
// those of linkwatch (twin of core/net/LinkWatch.kt): "connected" only after a probe came back
// through the new tunnel, then traffic proves the path or one tiny probe does; failures in a row
// climb from a second look to fresh sockets, a full rebuild and the next server the app listed.
type supervisor struct {
	t *tunnel
	// op serializes tunnel rebuilds: the app's up/down and the watchdog's own, so a repair that was
	// already running can never replace a tunnel the app has just asked for.
	op sync.Mutex

	mu         sync.Mutex
	specs      []*upSpec // the app's servers, preferred first
	active     int
	w          *linkwatch.Watch
	verified   bool
	everUp     bool // verified at least once in this session (a later loss is a repair)
	offline    bool
	reconnects int
	since      time.Time
	gen        int // bumped by start/stop: a stale timer does nothing
	timer      *time.Timer
}

func newSupervisor(t *tunnel) *supervisor { return &supervisor{t: t} }

// start takes over a tunnel the app has just brought up with specs[0].
func (s *supervisor) start(specs []*upSpec) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.gen++
	sameServer := len(s.specs) > 0 && s.specs[s.active].Name == specs[0].Name && s.everUp
	s.specs, s.active = specs, 0
	if s.w == nil || !sameServer {
		s.w = linkwatch.New(linkwatch.DefaultPolicy())
		s.everUp, s.reconnects = false, 0
		s.since = time.Now()
	}
	s.verified, s.offline = false, false
	s.w.Started(time.Now(), sameServer)
	s.scheduleLocked(150 * time.Millisecond)
}

func (s *supervisor) stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.gen++
	if s.timer != nil {
		s.timer.Stop()
	}
	s.specs, s.w, s.verified, s.everUp, s.offline = nil, nil, false, false, false
}

// recheck: the app saw something wrong (a site saw the real address): probe now.
func (s *supervisor) recheck() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.w != nil {
		s.w.Suspect()
		s.scheduleLocked(0)
	}
}

type watchStatus struct {
	Verified   bool   `json:"verified"`
	Repairing  bool   `json:"repairing,omitempty"`
	Phase      string `json:"phase,omitempty"`
	Offline    bool   `json:"offline,omitempty"`
	RTTMs      int64  `json:"rttMs,omitempty"`
	Reconnects int    `json:"reconnects,omitempty"`
	Active     int    `json:"active"`
	Server     string `json:"server,omitempty"`
	Session    int64  `json:"session,omitempty"`
}

func (s *supervisor) status() *watchStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.w == nil {
		return nil
	}
	return &watchStatus{
		Verified:   s.verified,
		Repairing:  !s.verified && s.everUp,
		Phase:      s.w.Phase.String(),
		Offline:    s.offline,
		RTTMs:      s.w.RTT.Milliseconds(),
		Reconnects: s.reconnects,
		Active:     s.active,
		Server:     s.specs[s.active].Name,
		Session:    s.since.UnixMilli(),
	}
}

func (s *supervisor) scheduleLocked(d time.Duration) {
	if s.timer != nil {
		s.timer.Stop()
	}
	gen := s.gen
	s.timer = time.AfterFunc(d, func() { s.look(gen) })
}

func (s *supervisor) look(gen int) {
	s.mu.Lock()
	if gen != s.gen || s.w == nil {
		s.mu.Unlock()
		return
	}
	spec := s.specs[s.active]
	s.mu.Unlock()

	now := time.Now()
	counters, luid, up := s.t.watchInfo()
	network := physicalNetwork(luid)
	var a linkwatch.Action
	s.mu.Lock()
	if gen != s.gen {
		s.mu.Unlock()
		return
	}
	switch {
	case !network:
		s.offline = true
		a = s.w.Offline(true)
		s.mu.Unlock()
		s.act(gen, spec, a)
		return
	case !up:
		a = linkwatch.Action{Kind: linkwatch.ActRebuild}
	default:
		a = s.w.OnCounters(now, counters, true)
	}
	s.offline = false
	s.mu.Unlock()

	if a.Kind == linkwatch.ActProbe {
		rtt, ok := linkwatch.Probe(probeTargets(spec), linkwatch.Targets, 2500*time.Millisecond, pinTo(luid))
		s.mu.Lock()
		if gen != s.gen {
			s.mu.Unlock()
			return
		}
		a = s.w.OnProbe(time.Now(), rtt, ok, true)
		if !ok {
			log.Printf("watch %q: no reply (%s, failure %d, builds %d)", spec.Name, s.w.Phase, s.w.Failures, s.w.FailedBuilds)
		}
		s.mu.Unlock()
	}
	s.act(gen, spec, a)
}

func (s *supervisor) act(gen int, spec *upSpec, a linkwatch.Action) {
	switch a.Kind {
	case linkwatch.ActWait:
		s.mu.Lock()
		if gen == s.gen {
			s.scheduleLocked(a.Delay)
		}
		s.mu.Unlock()
	case linkwatch.ActVerified:
		s.mu.Lock()
		if gen == s.gen {
			s.verified, s.everUp = true, true
			s.scheduleLocked(a.Delay)
		}
		s.mu.Unlock()
		log.Printf("watch %q: verified, %v", spec.Name, a.RTT.Round(time.Millisecond))
	case linkwatch.ActRebind:
		log.Printf("watch %q: fresh sockets", spec.Name)
		if s.t.rebind() == nil {
			s.mu.Lock()
			s.reconnects++
			s.mu.Unlock()
		}
		s.mu.Lock()
		if gen == s.gen && s.w != nil {
			s.w.Rebound()
			s.scheduleLocked(a.Delay)
		}
		s.mu.Unlock()
	case linkwatch.ActRebuild, linkwatch.ActFailover:
		s.mu.Lock()
		if gen != s.gen {
			s.mu.Unlock()
			return
		}
		next := s.active
		if a.Kind == linkwatch.ActFailover && len(s.specs) > 1 {
			next = (s.active + 1) % len(s.specs)
		}
		s.verified = false
		delay := a.Delay
		s.timer = time.AfterFunc(delay, func() { s.rebuild(gen, next) })
		s.mu.Unlock()
	}
}

// rebuild brings the tunnel up again with specs[index] (the same server, or the next one).
func (s *supervisor) rebuild(gen, index int) {
	s.mu.Lock()
	if gen != s.gen || index >= len(s.specs) {
		s.mu.Unlock()
		return
	}
	spec := s.specs[index]
	switched := index != s.active
	s.mu.Unlock()
	s.op.Lock()
	defer s.op.Unlock()
	s.mu.Lock()
	stale := gen != s.gen
	s.mu.Unlock()
	if stale {
		return
	}
	log.Printf("watch: rebuild %q (switch=%v)", spec.Name, switched)
	err := s.t.up(spec)
	s.mu.Lock()
	defer s.mu.Unlock()
	if gen != s.gen {
		return
	}
	s.reconnects++
	s.active = index
	if err != nil {
		log.Printf("watch: rebuild %q: %v", spec.Name, err)
		s.scheduleLocked(10 * time.Second)
		return
	}
	s.w.Started(time.Now(), !switched)
	s.scheduleLocked(150 * time.Millisecond)
}

// probeTargets: the tunnel's own resolvers first (they answer only through it), then public ones.
func probeTargets(spec *upSpec) []string {
	var out []string
	for _, line := range strings.Split(spec.DNSConfig, "\n") {
		if v, ok := strings.CutPrefix(strings.TrimSpace(line), "tunnel="); ok {
			out = append(out, strings.Split(v, ",")...)
		}
	}
	return append(out, linkwatch.Targets...)
}

// watchInfo: the counters of the running tunnel, its adapter, and whether it is up at all.
func (t *tunnel) watchInfo() (*linkwatch.Counters, winipcfg.LUID, bool) {
	t.mu.Lock()
	dev := t.dev
	t.mu.Unlock()
	luid := winipcfg.LUID(t.luid.Load())
	if dev == nil {
		return nil, luid, false
	}
	done := make(chan string, 1)
	go func() { s, _ := dev.IpcGet(); done <- s }()
	select {
	case s := <-done:
		return parseCounters(s), luid, true
	case <-time.After(5 * time.Second):
		return nil, luid, true // wedged: counts as a failure
	}
}

func parseCounters(uapi string) *linkwatch.Counters {
	c := &linkwatch.Counters{}
	var sec, nsec int64
	for _, line := range strings.Split(uapi, "\n") {
		k, v, _ := strings.Cut(line, "=")
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "rx_bytes":
			c.Rx += n
		case "tx_bytes":
			c.Tx += n
		case "last_handshake_time_sec":
			if n > sec {
				sec = n
			}
		case "last_handshake_time_nsec":
			nsec = n
		}
	}
	if sec > 0 {
		c.LastHandshake = time.Unix(sec, nsec)
	}
	return c
}

// physicalNetwork: some interface other than ours has a default route (the computer is online).
func physicalNetwork(ours winipcfg.LUID) bool {
	_, err := defaultInterface(windows.AF_INET, ours)
	return err == nil
}

// pinTo makes probe sockets leave through the tunnel adapter (IP_UNICAST_IF), whatever the routes
// say: a probe must prove the tunnel, never the physical network.
func pinTo(luid winipcfg.LUID) linkwatch.Control {
	row, err := luid.IPInterface(windows.AF_INET)
	if err != nil {
		return nil
	}
	index := row.InterfaceIndex
	return func(network, address string, c syscall.RawConn) error {
		var serr error
		err := c.Control(func(fd uintptr) {
			// IP_UNICAST_IF takes the index in network byte order for IPv4.
			be := (index&0xff)<<24 | (index&0xff00)<<8 | (index&0xff0000)>>8 | index>>24
			serr = windows.Setsockopt(windows.Handle(fd), windows.IPPROTO_IP, ipUnicastIf, (*byte)(unsafe.Pointer(&be)), 4)
		})
		if err != nil {
			return err
		}
		return serr
	}
}

const ipUnicastIf = 31
