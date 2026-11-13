package linkwatch

import (
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"
)

var t0 = time.Unix(1_700_000_000, 0)

func c(rx, tx int64, hsAgo time.Duration, now time.Time) *Counters {
	return &Counters{Rx: rx, Tx: tx, LastHandshake: now.Add(-hsAgo)}
}

func TestFreshTunnelIsNotTrustedUntilAProbeComesBack(t *testing.T) {
	w := New(DefaultPolicy())
	w.Started(t0, true)
	if a := w.OnCounters(t0, c(5000, 5000, time.Second, t0), true); a.Kind != ActProbe {
		t.Fatalf("counters alone verified: %+v", a)
	}
	if a := w.OnProbe(t0.Add(100*time.Millisecond), 0, false, true); a.Kind != ActWait || w.Phase != Verifying {
		t.Fatalf("%+v %v", a, w.Phase)
	}
	if a := w.OnProbe(t0.Add(900*time.Millisecond), 42*time.Millisecond, true, true); a.Kind != ActVerified || a.RTT != 42*time.Millisecond || w.Phase != Good {
		t.Fatalf("%+v %v", a, w.Phase)
	}
}

func TestDeadServerIsRebuiltThenFailedOver(t *testing.T) {
	w := New(DefaultPolicy())
	w.Started(t0, true)
	now := t0
	a := Action{Kind: ActProbe}
	for a.Kind != ActRebuild && a.Kind != ActFailover {
		now = now.Add(time.Second)
		a = w.OnProbe(now, 0, false, true)
	}
	if a.Kind != ActRebuild || a.Delay != 0 {
		t.Fatalf("first failed build: %+v", a)
	}
	w.Started(now, true)
	a = Action{Kind: ActProbe}
	for a.Kind != ActRebuild && a.Kind != ActFailover {
		now = now.Add(time.Second)
		a = w.OnProbe(now, 0, false, true)
	}
	if a.Kind != ActFailover || a.Delay <= 0 {
		t.Fatalf("second failed build: %+v", a)
	}
	w.Started(now, false)
	if w.FailedBuilds != 0 {
		t.Fatal("a switch keeps the old server's count")
	}
}

func TestLadderAndReset(t *testing.T) {
	w := New(DefaultPolicy())
	w.Started(t0, true)
	w.OnProbe(t0, 30*time.Millisecond, true, true)
	if a := w.OnProbe(t0.Add(time.Minute), 0, false, true); a.Kind != ActWait || w.Phase != Unstable {
		t.Fatalf("%+v", a)
	}
	if a := w.OnProbe(t0.Add(64*time.Second), 0, false, true); a.Kind != ActRebind {
		t.Fatalf("%+v", a)
	}
	if a := w.OnProbe(t0.Add(68*time.Second), 0, false, true); a.Kind != ActRebuild || a.Delay != 0 {
		t.Fatalf("%+v", a)
	}
	w2 := New(DefaultPolicy())
	w2.Started(t0, true)
	w2.OnProbe(t0, 30*time.Millisecond, true, true)
	w2.OnProbe(t0.Add(time.Minute), 0, false, true)
	if a := w2.OnProbe(t0.Add(64*time.Second), 35*time.Millisecond, true, true); a.Kind != ActWait || w2.Phase != Good {
		t.Fatalf("%+v", a)
	}
}

func TestBusyTunnelCostsNoProbes(t *testing.T) {
	w := New(DefaultPolicy())
	w.Started(t0, true)
	w.OnCounters(t0, c(0, 0, time.Second, t0), true)
	w.OnProbe(t0, 30*time.Millisecond, true, true)
	now := t0.Add(time.Minute)
	w.OnCounters(now, c(1000, 1000, 10*time.Second, now), true)
	now = now.Add(time.Minute)
	if a := w.OnCounters(now, c(51000, 20000, 30*time.Second, now), true); a.Kind != ActWait {
		t.Fatalf("traffic did not prove the path: %+v", a)
	}
	now = now.Add(time.Minute)
	if a := w.OnCounters(now, c(51000, 20000, 60*time.Second, now), false); a.Kind != ActWait {
		t.Fatalf("idle and quiet costs a probe: %+v", a)
	}
	now = now.Add(time.Minute)
	if a := w.OnCounters(now, c(51000, 25000, 90*time.Second, now), false); a.Kind != ActProbe {
		t.Fatalf("sent without reply: %+v", a)
	}
	w.Suspect()
	if a := w.OnCounters(now, c(51000, 25000, 30*time.Second, now), false); a.Kind != ActProbe {
		t.Fatalf("suspect: %+v", a)
	}
}

func TestProbeAnswersOnDNSAndGivesUpQuietly(t *testing.T) {
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	go func() {
		buf := make([]byte, 512)
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		buf[2] |= 0x80
		pc.WriteTo(buf[:n], from)
	}()
	port := uint16(pc.LocalAddr().(*net.UDPAddr).Port)
	if _, ok := ProbeAt([]netip.AddrPort{netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), port)}, nil, 2*time.Second, nil); !ok {
		t.Fatal("no answer from the local resolver")
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	tcpPort := uint16(ln.Addr().(*net.TCPAddr).Port)
	if _, ok := ProbeAt(nil, []netip.AddrPort{netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), tcpPort)}, 2*time.Second, nil); !ok {
		t.Fatal("no answer from the local TCP listener")
	}
	ln.Close()
	if _, ok := ProbeAt(nil, []netip.AddrPort{netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), tcpPort)}, 300*time.Millisecond, nil); ok {
		t.Fatal("answer from nowhere")
	}
	_ = binary.BigEndian
}
