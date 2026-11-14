//go:build windows

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"encoding/json"
	"log"
	"net"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/ipc/namedpipe"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"net.paramnezia/tunnel/dnsfilter"
)

// The app talks to the service over a local named pipe, one JSON request and one JSON answer per
// connection. Only local SYSTEM, administrators and interactively logged-on users may connect;
// network logons are denied outright (the deny ACE comes first, so it wins even for an
// administrator connecting over SMB). The listener is amneziawg-go's own named pipe code (overlapped
// I/O, deadlines), which the tunnel already carries, so no separate pipe library is needed.
const pipeSDDL = "D:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)"

type request struct {
	Op string `json:"op"` // up, down, rebind, recheck, status, set, rules, direct, version
	// up: the server to connect to, and the others to fall back on, preferred first. The service
	// watches the tunnel itself and switches to the next one when a server stays dead.
	Up         *upSpec   `json:"up,omitempty"`
	Alternates []*upSpec `json:"alternates,omitempty"`
	// set: a partial UAPI set on the running tunnel (keepalive)
	UAPI string `json:"uapi,omitempty"`
	// rules: DNS filter lists, one rule per line
	Block  string `json:"block,omitempty"`
	Direct string `json:"direct,omitempty"`
	Allow  string `json:"allow,omitempty"`
}

type upSpec struct {
	Name string `json:"name"`
	// UAPI is the full amneziawg-go configuration (keys, AmneziaWG parameters, peers).
	UAPI      string   `json:"uapi"`
	Addresses []string `json:"addresses"`
	// Routes into the tunnel; everything else stays on the physical network.
	Routes []string `json:"routes"`
	// DNSConfig enables the filter ("virtual=…\ntunnel=…\ndirect=…"); empty uses DNSServers as is.
	DNSConfig string `json:"dnsConfig,omitempty"`
	// DNSServers go on the adapter; with the filter that is the virtual resolver.
	DNSServers []string `json:"dnsServers"`
	DNSSearch  []string `json:"dnsSearch,omitempty"`
	MTU        int      `json:"mtu,omitempty"`
	// ExcludedApps go past the VPN: program file names ("telegram.exe") or full paths.
	ExcludedApps []string `json:"excludedApps,omitempty"`
}

type response struct {
	OK      bool   `json:"ok"`
	Error   string `json:"error,omitempty"`
	Up      bool   `json:"up,omitempty"`
	Name    string `json:"name,omitempty"`
	Since   int64  `json:"since,omitempty"`
	Stats   string `json:"stats,omitempty"`
	Blocked int64  `json:"blocked,omitempty"`
	// direct: a SOCKS5 proxy on 127.0.0.1 whose connections leave over the physical network.
	Socks     string `json:"socks,omitempty"`
	SocksUser string `json:"socksUser,omitempty"`
	SocksPass string `json:"socksPass,omitempty"`
	Version   string `json:"version,omitempty"`
	// status: what the service's watchdog knows (verified, repairing, which server is active).
	Watch *watchStatus `json:"watch,omitempty"`
}

type server struct {
	ln     net.Listener
	tunnel *tunnel
	watch  *supervisor
	mu     sync.Mutex
	direct *directProxy
	wg     sync.WaitGroup
}

func startServer() (*server, error) {
	sd, err := windows.SecurityDescriptorFromString(pipeSDDL)
	if err != nil {
		return nil, err
	}
	ln, err := (&namedpipe.ListenConfig{
		SecurityDescriptor: sd,
		InputBufferSize:    1 << 16,
		OutputBufferSize:   1 << 16,
	}).Listen(pipeName)
	if err != nil {
		return nil, err
	}
	t := &tunnel{}
	s := &server{ln: ln, tunnel: t, watch: newSupervisor(t)}
	s.wg.Add(1)
	go s.accept()
	return s, nil
}

func (s *server) accept() {
	defer s.wg.Done()
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		go s.serve(c)
	}
}

func (s *server) serve(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(2 * time.Minute))
	var req request
	if err := json.NewDecoder(c).Decode(&req); err != nil {
		return
	}
	resp := s.handle(&req)
	_ = json.NewEncoder(c).Encode(resp)
}

func (s *server) handle(r *request) response {
	fail := func(err error) response { return response{Error: err.Error()} }
	switch r.Op {
	case "version":
		return response{OK: true, Version: amneziawgVersion()}
	case "up":
		if r.Up == nil {
			return response{Error: "no spec"}
		}
		s.watch.op.Lock()
		defer s.watch.op.Unlock()
		s.watch.stop()
		if err := s.tunnel.up(r.Up); err != nil {
			log.Printf("up %q: %v", r.Up.Name, err)
			return fail(err)
		}
		specs := []*upSpec{r.Up}
		for _, a := range r.Alternates {
			if a != nil && a.Name != r.Up.Name {
				specs = append(specs, a)
			}
		}
		s.watch.start(specs)
		log.Printf("up %q: %d routes, %d fallback servers", r.Up.Name, len(r.Up.Routes), len(specs)-1)
		return response{OK: true}
	case "down":
		s.watch.op.Lock()
		defer s.watch.op.Unlock()
		s.watch.stop()
		s.tunnel.down()
		log.Printf("down")
		return response{OK: true}
	case "rebind":
		if err := s.tunnel.rebind(); err != nil {
			return fail(err)
		}
		log.Printf("rebind")
		return response{OK: true}
	case "recheck":
		s.watch.recheck()
		return response{OK: true}
	case "status":
		st := s.tunnel.status()
		st.Watch = s.watch.status()
		st.OK = true
		st.Blocked = dnsfilter.Blocked()
		return st
	case "set":
		if err := s.tunnel.set(r.UAPI); err != nil {
			return fail(err)
		}
		return response{OK: true}
	case "rules":
		dnsfilter.SetRules(r.Block, r.Direct, r.Allow)
		return response{OK: true}
	case "direct":
		p, err := s.directProxy()
		if err != nil {
			return fail(err)
		}
		return response{OK: true, Socks: p.addr, SocksUser: p.user, SocksPass: p.pass}
	}
	return response{Error: "unknown op " + r.Op}
}

func (s *server) directProxy() (*directProxy, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.direct != nil {
		return s.direct, nil
	}
	p, err := startDirectProxy(func() winipcfg.LUID { return winipcfg.LUID(s.tunnel.luid.Load()) })
	if err != nil {
		return nil, err
	}
	s.direct = p
	return p, nil
}

func (s *server) close() {
	s.ln.Close()
	s.watch.stop()
	s.tunnel.down()
	s.mu.Lock()
	if s.direct != nil {
		s.direct.close()
	}
	s.mu.Unlock()
}
