/* SPDX-License-Identifier: Apache-2.0 */

// Package linkwatch decides, from cheap observations, whether a tunnel really carries traffic and
// what to do when it does not. It is the Go twin of core/net/LinkWatch.kt (same rules, same
// numbers): the Windows service runs it itself, so the tunnel is watched and repaired even while
// the app is closed. See the Kotlin file for the reasoning; in short:
//
//   - a fresh tunnel is verified by probes before it counts as connected;
//   - traffic that came back proves the path, an idle tunnel gets one tiny probe;
//   - failures in a row: a second look, fresh sockets, a full rebuild, another server.
package linkwatch

import "time"

type Policy struct {
	VerifyFor      time.Duration
	VerifySteps    []time.Duration
	ProvenRxBytes  int64
	StaleHandshake time.Duration
	RTTMaxAge      time.Duration
	WatchActive    time.Duration
	WatchIdle      time.Duration
	Confirm        time.Duration
	RebindAfter    int
	RebuildAfter   int
	FailoverAfter  int
	BackoffBase    time.Duration
	BackoffMax     time.Duration
	BackoffMaxIdle time.Duration
}

func DefaultPolicy() Policy {
	return Policy{
		VerifyFor:      20 * time.Second,
		VerifySteps:    []time.Duration{250 * time.Millisecond, 750 * time.Millisecond, time.Second, 1500 * time.Millisecond, 2 * time.Second, 3 * time.Second},
		ProvenRxBytes:  2048,
		StaleHandshake: 180 * time.Second,
		RTTMaxAge:      5 * time.Minute,
		WatchActive:    time.Minute,
		WatchIdle:      15 * time.Minute,
		Confirm:        4 * time.Second,
		RebindAfter:    2,
		RebuildAfter:   3,
		FailoverAfter:  2,
		BackoffBase:    5 * time.Second,
		BackoffMax:     5 * time.Minute,
		BackoffMaxIdle: 15 * time.Minute,
	}
}

type Counters struct {
	Rx, Tx        int64
	LastHandshake time.Time // zero: never
}

type Phase int

const (
	Verifying Phase = iota
	Good
	Unstable
)

func (p Phase) String() string {
	switch p {
	case Good:
		return "good"
	case Unstable:
		return "unstable"
	}
	return "verifying"
}

type Kind int

const (
	ActWait     Kind = iota // look again after Delay
	ActProbe                // send a probe now and report it with OnProbe
	ActVerified             // the first answer of a fresh tunnel (RTT set); look again after Delay
	ActRebind               // fresh sockets in the same tunnel, then look again after Delay
	ActRebuild              // build the tunnel again after Delay
	ActFailover             // switch to another server if there is one, else rebuild after Delay
)

type Action struct {
	Kind  Kind
	Delay time.Duration
	RTT   time.Duration
}

type Watch struct {
	P            Policy
	Phase        Phase
	RTT          time.Duration // smoothed; 0 until a probe came back
	RTTAt        time.Time
	Failures     int
	FailedBuilds int

	verifyStart time.Time
	verifyStep  int
	last        *Counters
	suspected   bool
}

func New(p Policy) *Watch { return &Watch{P: p} }

// Started: a (re)built tunnel, to be verified before it is trusted; sameServer=false after a switch.
func (w *Watch) Started(now time.Time, sameServer bool) {
	w.Phase, w.verifyStart, w.verifyStep, w.Failures, w.last = Verifying, now, 0, 0, nil
	if !sameServer {
		w.FailedBuilds = 0
	}
}

// Rebound: the sockets were replaced; the next look starts fresh.
func (w *Watch) Rebound() { w.last = nil }

// Suspect: something hints at trouble (network changed, the app asked); the next look probes.
func (w *Watch) Suspect() { w.suspected, w.last = true, nil }

// OnCounters is a look at the counters (no traffic). nil means the tunnel did not answer at all.
func (w *Watch) OnCounters(now time.Time, c *Counters, active bool) Action {
	if c == nil {
		return w.OnProbe(now, 0, false, active)
	}
	prev := w.last
	cc := *c
	w.last = &cc
	if w.Phase == Verifying {
		return Action{Kind: ActProbe}
	}
	stale := c.LastHandshake.IsZero() || now.Sub(c.LastHandshake) > w.P.StaleHandshake
	var rx int64
	if prev != nil {
		rx = c.Rx - prev.Rx
	}
	sentWithoutReply := prev != nil && c.Tx > prev.Tx && rx <= 0
	if w.Phase == Unstable || w.suspected {
		w.suspected = false
		return Action{Kind: ActProbe}
	}
	proven := rx >= w.P.ProvenRxBytes && !stale
	rttOld := now.Sub(w.RTTAt) > w.P.RTTMaxAge
	look := false
	switch {
	case stale || sentWithoutReply:
		look = true
	case proven:
		look = active && rttOld
	default:
		look = active
	}
	if look {
		return Action{Kind: ActProbe}
	}
	return Action{Kind: ActWait, Delay: w.Interval(active)}
}

// OnProbe reports a probe: ok with its round trip, or no answer.
func (w *Watch) OnProbe(now time.Time, rtt time.Duration, ok bool, active bool) Action {
	if ok {
		if w.RTT == 0 {
			w.RTT = rtt
		} else {
			w.RTT = (w.RTT*3 + rtt) / 4
		}
		w.RTTAt, w.Failures, w.suspected = now, 0, false
		wasVerifying := w.Phase == Verifying
		w.Phase = Good
		if wasVerifying {
			w.FailedBuilds = 0
			return Action{Kind: ActVerified, RTT: rtt, Delay: w.Interval(active)}
		}
		return Action{Kind: ActWait, Delay: w.Interval(active)}
	}
	if w.Phase == Verifying {
		if now.Sub(w.verifyStart) < w.P.VerifyFor {
			i := w.verifyStep
			if i >= len(w.P.VerifySteps) {
				i = len(w.P.VerifySteps) - 1
			}
			w.verifyStep++
			return Action{Kind: ActWait, Delay: w.P.VerifySteps[i]}
		}
		w.FailedBuilds++
		d := w.backoff(w.FailedBuilds-1, active)
		if w.FailedBuilds >= w.P.FailoverAfter {
			return Action{Kind: ActFailover, Delay: d}
		}
		return Action{Kind: ActRebuild, Delay: d}
	}
	w.Failures++
	w.Phase = Unstable
	switch {
	case w.Failures >= w.P.RebuildAfter:
		return Action{Kind: ActRebuild}
	case w.Failures >= w.P.RebindAfter:
		return Action{Kind: ActRebind, Delay: w.P.Confirm}
	}
	return Action{Kind: ActWait, Delay: w.P.Confirm}
}

// Offline: the device has no working network; nothing counts, look again later.
func (w *Watch) Offline(active bool) Action {
	w.last = nil
	if w.Phase == Verifying {
		return Action{Kind: ActWait, Delay: w.P.Confirm}
	}
	d := w.Interval(active)
	if d > w.P.WatchActive {
		d = w.P.WatchActive
	}
	return Action{Kind: ActWait, Delay: d}
}

func (w *Watch) Interval(active bool) time.Duration {
	if active {
		return w.P.WatchActive
	}
	return w.P.WatchIdle
}

func (w *Watch) backoff(n int, active bool) time.Duration {
	if n <= 0 {
		return 0
	}
	if n > 11 {
		n = 11
	}
	d := w.P.BackoffBase << (n - 1)
	max := w.P.BackoffMax
	if !active {
		max = w.P.BackoffMaxIdle
	}
	if d > max {
		d = max
	}
	return d
}
