/* SPDX-License-Identifier: Apache-2.0 */

package splitapp

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

func TestRules(t *testing.T) {
	r := NewRules([]string{"Telegram.exe", `C:\Games\Steam\steam.exe`, " ", "d:/tools/curl.exe"})
	for path, want := range map[string]bool{
		`C:\Users\a\AppData\Roaming\Telegram Desktop\Telegram.exe`: true,
		`C:\Games\Steam\Steam.exe`:                                 true,
		`C:\Other\steam.exe`:                                       false,
		`D:\Tools\curl.exe`:                                        true,
		`C:\Windows\curl.exe`:                                      false,
		`C:\Program Files\x\tg.exe`:                                false,
		"":                                                         false,
	} {
		if got := r.Match(path); got != want {
			t.Errorf("Match(%q) = %v, want %v", path, got, want)
		}
	}
	if !NewRules(nil).Empty() || NewRules([]string{"a.exe"}).Empty() {
		t.Error("Empty")
	}
}

// fakeDialer connects every "direct" flow to a local echo server and remembers where the program
// wanted to go.
type fakeDialer struct {
	tcpEcho, udpEcho string
	mu               sync.Mutex
	dialed           []netip.AddrPort
}

func (d *fakeDialer) DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	d.mu.Lock()
	d.dialed = append(d.dialed, dst)
	d.mu.Unlock()
	return (&net.Dialer{}).DialContext(ctx, "tcp", d.tcpEcho)
}

func (d *fakeDialer) ListenUDP(v6 bool) (net.PacketConn, error) {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return nil, err
	}
	return &mappedUDP{PacketConn: c, d: d}, nil
}

// mappedUDP sends to the echo server and makes its answers look as if the destination sent them.
type mappedUDP struct {
	net.PacketConn
	d    *fakeDialer
	mu   sync.Mutex
	last net.Addr
}

func (m *mappedUDP) WriteTo(b []byte, to net.Addr) (int, error) {
	m.mu.Lock()
	m.last = to
	m.mu.Unlock()
	m.d.mu.Lock()
	m.d.dialed = append(m.d.dialed, to.(*net.UDPAddr).AddrPort())
	m.d.mu.Unlock()
	echo, _ := net.ResolveUDPAddr("udp", m.d.udpEcho)
	return m.PacketConn.WriteTo(b, echo)
}

func (m *mappedUDP) ReadFrom(b []byte) (int, net.Addr, error) {
	n, _, err := m.PacketConn.ReadFrom(b)
	m.mu.Lock()
	defer m.mu.Unlock()
	return n, m.last, err
}

func echoServers(t *testing.T) (string, string) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	uc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { uc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := uc.ReadFrom(buf)
			if err != nil {
				return
			}
			uc.WriteTo(buf[:n], from)
		}
	}()
	return ln.Addr().String(), uc.LocalAddr().String()
}

// The "program" is a second user-space stack whose packets play the part of Windows' packets into
// Wintun; whatever the split tunnel lets through is what amneziawg-go would get.
func TestSplit(t *testing.T) {
	tcpEcho, udpEcho := echoServers(t)
	appTun, app, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.9.0.2")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dialer := &fakeDialer{tcpEcho: tcpEcho, udpEcho: udpEcho}
	owner := func(proto uint8, local, remote netip.AddrPort) string {
		if remote.Port() == 7001 {
			return `C:\Apps\Excluded.exe`
		}
		return `C:\Apps\Other.exe`
	}
	dev, err := NewTun(appTun, Config{
		Rules: NewRules([]string{"excluded.exe"}), Owner: owner, Dialer: dialer, MTU: 1280,
		Keep: []netip.Prefix{netip.MustParsePrefix("10.9.0.0/24")}, Logf: t.Logf,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer dev.Close()

	tunnel := make(chan flowKey, 64)
	go func() {
		bufs := [][]byte{make([]byte, 2048)}
		sizes := []int{0}
		for {
			n, err := dev.Read(bufs, sizes, 0)
			if err != nil {
				return
			}
			for i := 0; i < n; i++ {
				if k, _, ok := parseFlow(bufs[i][:sizes[i]]); ok {
					tunnel <- k
				}
			}
		}
	}()
	inTunnel := func(dst string) bool {
		want := netip.MustParseAddrPort(dst)
		deadline := time.After(3 * time.Second)
		for {
			select {
			case k := <-tunnel:
				if k.dst == want {
					return true
				}
			case <-deadline:
				return false
			}
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	t.Run("excluded TCP goes direct", func(t *testing.T) {
		c, err := app.DialContextTCPAddrPort(ctx, netip.MustParseAddrPort("203.0.113.5:7001"))
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		msg := bytes.Repeat([]byte("paramnezia "), 20000) // several hundred KB, many segments
		go c.Write(msg)
		got := make([]byte, len(msg))
		c.SetReadDeadline(time.Now().Add(5 * time.Second))
		if _, err := io.ReadFull(c, got); err != nil || !bytes.Equal(got, msg) {
			t.Fatalf("echo: %v", err)
		}
	})
	t.Run("excluded UDP goes direct", func(t *testing.T) {
		c, err := app.DialUDPAddrPort(netip.AddrPort{}, netip.MustParseAddrPort("203.0.113.6:7001"))
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		if _, err := c.Write([]byte("hello")); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 64)
		c.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := c.Read(buf)
		if err != nil || string(buf[:n]) != "hello" {
			t.Fatalf("udp echo: %q %v", buf[:n], err)
		}
	})
	t.Run("other programs stay in the tunnel", func(t *testing.T) {
		go func() {
			c, err := app.DialContextTCPAddrPort(ctx, netip.MustParseAddrPort("203.0.113.7:7002"))
			if err == nil {
				c.Close()
			}
		}()
		if !inTunnel("203.0.113.7:7002") {
			t.Fatal("SYN of a program not on the list did not reach the tunnel")
		}
		c, _ := app.DialUDPAddrPort(netip.AddrPort{}, netip.MustParseAddrPort("203.0.113.8:7002"))
		c.Write([]byte("x"))
		defer c.Close()
		if !inTunnel("203.0.113.8:7002") {
			t.Fatal("UDP of a program not on the list did not reach the tunnel")
		}
	})
	t.Run("tunnel subnet stays in the tunnel", func(t *testing.T) {
		c, _ := app.DialUDPAddrPort(netip.AddrPort{}, netip.MustParseAddrPort("10.9.0.1:7001"))
		c.Write([]byte("x"))
		defer c.Close()
		if !inTunnel("10.9.0.1:7001") {
			t.Fatal("excluded program's packet to the tunnel subnet left the tunnel")
		}
	})

	dialer.mu.Lock()
	defer dialer.mu.Unlock()
	for _, d := range dialer.dialed {
		if d.Port() != 7001 || d.Addr().String() == "10.9.0.1" {
			t.Errorf("dialed %v directly", d)
		}
	}
	if len(dialer.dialed) < 2 {
		t.Errorf("direct dials: %v", dialer.dialed)
	}
}
