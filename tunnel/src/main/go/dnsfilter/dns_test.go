package dnsfilter

import (
	"encoding/binary"
	"net"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

func query(id uint16, name string, qtype uint16) []byte {
	m := []byte{byte(id >> 8), byte(id), 0x01, 0, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, l := range splitDots(name) {
		m = append(m, byte(len(l)))
		m = append(m, l...)
	}
	return append(m, 0, byte(qtype>>8), byte(qtype), 0, 1)
}

func splitDots(s string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(s); i++ {
		if i == len(s) || s[i] == '.' {
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	return out
}

func udpPacket(src, dst [4]byte, sport, dport uint16, payload []byte) []byte {
	p := make([]byte, 28+len(payload))
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:], uint16(len(p)))
	p[8], p[9] = 64, 17
	copy(p[12:], src[:])
	copy(p[16:], dst[:])
	binary.BigEndian.PutUint16(p[20:], sport)
	binary.BigEndian.PutUint16(p[22:], dport)
	binary.BigEndian.PutUint16(p[24:], uint16(8+len(payload)))
	copy(p[28:], payload)
	return p
}

// fakeTun feeds packets from in to Read and collects written packets in out.
type fakeTun struct {
	in  chan []byte
	out chan []byte
}

func (f *fakeTun) File() *os.File           { return nil }
func (f *fakeTun) MTU() (int, error)        { return 1280, nil }
func (f *fakeTun) Name() (string, error)    { return "fake", nil }
func (f *fakeTun) Events() <-chan tun.Event { return nil }
func (f *fakeTun) Close() error             { return nil }
func (f *fakeTun) BatchSize() int           { return 2 }
func (f *fakeTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	n := 0
	for n < len(bufs) {
		var p []byte
		if n == 0 {
			p = <-f.in
		} else {
			select {
			case p = <-f.in:
			default:
				return n, nil
			}
		}
		sizes[n] = copy(bufs[n][offset:], p)
		n++
	}
	return n, nil
}
func (f *fakeTun) Write(bufs [][]byte, offset int) (int, error) {
	for _, b := range bufs {
		f.out <- append([]byte(nil), b[offset:]...)
	}
	return len(bufs), nil
}

// fakeResolver answers every A query with addr and TTL 60.
func fakeResolver(t *testing.T, addr [4]byte) (string, func() int) {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	var count atomic.Int32
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := c.ReadFromUDP(buf)
			if err != nil {
				return
			}
			count.Add(1)
			_, _, qend, _ := parseQuestion(buf[:n])
			r := append([]byte(nil), buf[:qend]...)
			r[2] |= 0x80
			r[3] = 0x80
			binary.BigEndian.PutUint16(r[6:], 1)
			r = append(r, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
			r = append(r, addr[:]...)
			c.WriteToUDP(r, from)
		}
	}()
	return c.LocalAddr().String(), func() int { return int(count.Load()) }
}

func TestRules(t *testing.T) {
	r := parseRules("mc.yandex.ru\nfull:an.yandex.ru\n# c\n", "ru\nyandex.net\n", "full:good.mc.yandex.ru")
	cases := map[string]uint8{
		"mc.yandex.ru": ruleBlock | ruleDirect, "x.mc.yandex.ru": ruleBlock | ruleDirect,
		"an.yandex.ru": ruleBlock | ruleDirect, "x.an.yandex.ru": ruleDirect,
		"good.mc.yandex.ru": ruleBlock | ruleDirect | ruleAllow,
		"strm.yandex.net":   ruleDirect, "example.com": 0,
	}
	for name, want := range cases {
		if got := r.classify(name); got != want {
			t.Errorf("%s: %b, want %b", name, got, want)
		}
	}
}

func TestBlockedAnswer(t *testing.T) {
	q := query(0x1234, "Mc.Yandex.RU", 1)
	name, qtype, qend, ok := parseQuestion(q)
	if !ok || name != "mc.yandex.ru" || qtype != 1 || qend != len(q) {
		t.Fatalf("parse: %q %d %d %v", name, qtype, qend, ok)
	}
	a := blockedAnswer(q, 1, qend)
	if binary.BigEndian.Uint16(a) != 0x1234 || a[2]&0x80 == 0 || binary.BigEndian.Uint16(a[6:]) != 1 || len(a) != len(q)+16 {
		t.Fatalf("bad answer % x", a)
	}
	if ttl := minTTL(a); ttl != 3600 && ttl != 600 {
		t.Fatalf("ttl %d", ttl)
	}
}

func TestSplitDNS(t *testing.T) {
	tunnelAddr, tunnelCount := fakeResolver(t, [4]byte{1, 1, 1, 1})
	directAddr, _ := fakeResolver(t, [4]byte{77, 88, 55, 60})
	dc, _ := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})

	rules.Store(parseRules("mc.yandex.ru", "ru", ""))
	ft := &fakeTun{in: make(chan []byte, 8), out: make(chan []byte, 8)}
	cfg, ok := ParseConfig("virtual=198.18.0.53\ntunnel=" + tunnelAddr + "\ndirect=" + directAddr)
	if !ok {
		t.Fatal("config")
	}
	dt, err := NewTun(ft, cfg, dc)
	if err != nil {
		t.Fatal(err)
	}
	client := [4]byte{10, 8, 1, 2}
	vdns := [4]byte{198, 18, 0, 53}
	other := udpPacket(client, [4]byte{8, 8, 8, 8}, 5000, 443, []byte("data"))

	passed := make(chan []byte, 8)
	go func() {
		bufs := [][]byte{make([]byte, 2000), make([]byte, 2000)}
		sizes := make([]int, 2)
		for {
			n, _ := dt.Read(bufs, sizes, 0)
			for i := 0; i < n; i++ {
				passed <- append([]byte(nil), bufs[i][:sizes[i]]...)
			}
		}
	}()

	answer := func(name string, id uint16) []byte {
		ft.in <- udpPacket(client, vdns, 40000+id, 53, query(id, name, 1))
		select {
		case p := <-ft.out:
			if p[12] != 198 || p[16] != 10 || binary.BigEndian.Uint16(p[22:]) != 40000+id || binary.BigEndian.Uint16(p[28:]) != id {
				t.Fatalf("bad reply header % x", p[:32])
			}
			return p[28:]
		case <-time.After(5 * time.Second):
			t.Fatalf("no answer for %s", name)
		}
		return nil
	}
	last4 := func(m []byte) [4]byte { var a [4]byte; copy(a[:], m[len(m)-4:]); return a }

	ft.in <- other
	if p := <-passed; string(p[28:]) != "data" {
		t.Fatal("ordinary packet changed")
	}
	if a := last4(answer("mc.yandex.ru", 1)); a != [4]byte{} {
		t.Errorf("tracker not blocked: %v", a)
	}
	if a := last4(answer("gosuslugi.ru", 2)); a != [4]byte{77, 88, 55, 60} {
		t.Errorf("russian name not resolved directly: %v", a)
	}
	if a := last4(answer("example.com", 3)); a != [4]byte{1, 1, 1, 1} {
		t.Errorf("foreign name not resolved in tunnel: %v", a)
	}
	before := tunnelCount()
	if a := last4(answer("example.com", 4)); a != [4]byte{1, 1, 1, 1} || tunnelCount() != before {
		t.Errorf("cache miss: %v", a)
	}
	// Local names: through the tunnel while the local resolver is unknown, then to the router.
	SetLocalServers(nil)
	if a := last4(answer("nas.lan", 5)); a != [4]byte{1, 1, 1, 1} {
		t.Errorf("local name without a local resolver: %v", a)
	}
	localAddr, localCount := fakeResolver(t, [4]byte{192, 168, 1, 10})
	SetLocalServers([]string{localAddr, "fe80::1"})
	for i, name := range []string{"nas.lan", "printer.local", "nas", "my.keenetic.net", "10.1.168.192.in-addr.arpa", "20.17.172.in-addr.arpa"} {
		if a := last4(answer(name, uint16(10+i))); a != [4]byte{192, 168, 1, 10} {
			t.Errorf("%s not resolved by the local network: %v", name, a)
		}
	}
	if a := last4(answer("example.lan.ru", 20)); a == [4]byte{192, 168, 1, 10} {
		t.Errorf("non-local name sent to the router")
	}
	if a := last4(answer("1.32.172.in-addr.arpa", 21)); a == [4]byte{192, 168, 1, 10} {
		t.Errorf("public reverse zone sent to the router")
	}
	seen := localCount()
	SetLocalServers(nil)
	if a := last4(answer("nas.lan", 22)); a != [4]byte{1, 1, 1, 1} || localCount() != seen {
		t.Errorf("stale local answer after leaving the network: %v", a)
	}
	if blockedCount.Load() < 1 {
		t.Error("blocked counter")
	}
	select {
	case p := <-passed:
		t.Fatalf("DNS query leaked into the tunnel: % x", p[:20])
	default:
	}
}
