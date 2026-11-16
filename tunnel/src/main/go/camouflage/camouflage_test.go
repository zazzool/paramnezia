// Package camouflage holds no code: its test runs the real amneziawg-go with the client settings the
// app's Camouflage (core/src/main/kotlin/net/paramnezia/core/config/Camouflage.kt) produces, and
// checks them on the wire.
package camouflage

import (
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/tuntest"
	"golang.org/x/crypto/curve25519"
)

// Kept in step with Camouflage.STUN_BINDING and Camouflage.dtlsClientHello() (CamouflageTest checks
// the same bytes from the Kotlin side).
const (
	stun = "<b 0x000100002112a442><r 12>"
	dtls = "<b 0x16feff00000000000000000090010000840000000000000084fefd><r 32><b 0x00000018c02bc02fcca9cca8c009c013c00ac014009c009d002f00350100004200170000ff01000100000a00080006001d00170018000b0002010000230000000d00140012040308040401050308050501080606010201000e000700040007000100>"
)

func key() (priv, pub string) {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	b[0] &= 248
	b[31] = (b[31] & 127) | 64
	pk, _ := curve25519.X25519(b, curve25519.Basepoint)
	return hex.EncodeToString(b), hex.EncodeToString(pk)
}

// proxy relays between a client and a server and keeps every datagram the client sent.
type proxy struct {
	mu   sync.Mutex
	sent [][]byte
}

func (p *proxy) run(t *testing.T, ln *net.UDPConn, server *net.UDPAddr) {
	var client *net.UDPAddr
	up, err := net.DialUDP("udp4", nil, server)
	if err != nil {
		t.Error(err)
		return
	}
	go func() {
		buf := make([]byte, 65536)
		for {
			n, err := up.Read(buf)
			if err != nil {
				return
			}
			p.mu.Lock()
			c := client
			p.mu.Unlock()
			if c != nil {
				_, _ = ln.WriteToUDP(buf[:n], c)
			}
		}
	}()
	buf := make([]byte, 65536)
	for {
		n, from, err := ln.ReadFromUDP(buf)
		if err != nil {
			return
		}
		p.mu.Lock()
		client = from
		p.sent = append(p.sent, append([]byte(nil), buf[:n]...))
		p.mu.Unlock()
		_, _ = up.Write(buf[:n])
	}
}

// shared server parameters (AmneziaWG 3 as the deploy writes it).
var shared = "s1=12\ns2=12\ns3=12\ns4=12\nh1=1\nh2=2\nh3=3\nh4=4\nheader_protection_key=" + strings.Repeat("ab", 32) + "\nrandom_trailers=true\n"

type pair struct {
	cli, srv       *device.Device
	cliTun, srvTun *tuntest.ChannelTUN
	px             *proxy
}

// newPair: a server, and a client with [client] settings that reaches it through a recording proxy.
func newPair(t *testing.T, client string) *pair {
	srvPriv, srvPub := key()
	cliPriv, cliPub := key()
	psk := strings.Repeat("5a", 32)
	p := &pair{srvTun: tuntest.NewChannelTUN(), cliTun: tuntest.NewChannelTUN(), px: &proxy{}}
	p.srv = device.NewDevice(p.srvTun.TUN(), conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "srv "))
	t.Cleanup(p.srv.Close)
	srvPort := freePort(t)
	if err := p.srv.IpcSet("private_key=" + srvPriv + "\nlisten_port=" + fmt.Sprint(srvPort) + "\njc=4\njmin=10\njmax=50\n" + shared + "content_padding_addition=0-31\n" +
		"public_key=" + cliPub + "\npreshared_key=" + psk + "\nallowed_ip=10.8.1.2/32\n"); err != nil {
		t.Fatal(err)
	}
	if err := p.srv.Up(); err != nil {
		t.Fatal(err)
	}
	ln, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go p.px.run(t, ln, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: srvPort})
	p.cli = device.NewDevice(p.cliTun.TUN(), conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "cli "))
	t.Cleanup(p.cli.Close)
	if err := p.cli.IpcSet("private_key=" + cliPriv + "\n" + shared + client +
		"public_key=" + srvPub + "\npreshared_key=" + psk + "\nendpoint=" + ln.LocalAddr().String() + "\nallowed_ip=10.8.1.1/32\n"); err != nil {
		t.Fatal(err)
	}
	if err := p.cli.Up(); err != nil {
		t.Fatal(err)
	}
	return p
}

// ping sends a small packet from the client and waits for it at the server, sending again each
// second like an app would (the first packet of a fresh tunnel waits for a handshake that a busy
// test machine can lose; WireGuard retries it only after 5 s).
func (p *pair) ping(t *testing.T) {
	t.Helper()
	for try := 0; try < 20; try++ {
		p.cliTun.Outbound <- tuntest.Ping(netip.MustParseAddr("10.8.1.1"), netip.MustParseAddr("10.8.1.2"))
		select {
		case got := <-p.srvTun.Inbound:
			if len(got) < 20 {
				t.Fatalf("short packet %d", len(got))
			}
			return
		case <-time.After(time.Second):
		}
	}
	t.Fatal("no data through the tunnel")
}

func (p *pair) sent() [][]byte {
	p.px.mu.Lock()
	defer p.px.mu.Unlock()
	return append([][]byte(nil), p.px.sent...)
}

func TestClientCamouflageOnTheWire(t *testing.T) {
	for _, tc := range []struct {
		name string
		ip   string // signature packets
		dtls bool
	}{
		{"stun", "i1=" + stun + "\n", false},
		{"webrtc", "i1=" + stun + "\ni2=" + dtls + "\n", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			// The client side of Camouflage.apply: two junk packets after the signatures, heavy padding first.
			p := newPair(t, "jc=2\njmin=40\njmax=120\n"+tc.ip+"content_padding_addition=24-255\n")
			p.ping(t)
			// The steady padding, set on the running tunnel as the app does after the start of the flow.
			if err := p.cli.IpcSet("content_padding_addition=0-31\n"); err != nil {
				t.Fatal(err)
			}
			p.ping(t)
			sent := p.sent()
			if len(sent) < 3 {
				t.Fatalf("only %d datagrams", len(sent))
			}
			first := sent[0]
			if len(first) != 20 || binary.BigEndian.Uint16(first) != 0x0001 || binary.BigEndian.Uint32(first[4:]) != 0x2112a442 {
				t.Fatalf("first datagram is no STUN Binding Request: %x", first)
			}
			if tc.dtls {
				d := sent[1]
				if d[0] != 0x16 || binary.BigEndian.Uint16(d[1:]) != 0xfeff || int(binary.BigEndian.Uint16(d[11:]))+13 != len(d) || d[13] != 0x01 {
					t.Fatalf("second datagram is no DTLS ClientHello: %x", d[:min(len(d), 32)])
				}
			}
		})
	}
}

// bigPacket: a 1300-byte IPv4/UDP packet from the server's side to the client (a download).
func bigPacket() []byte {
	b := make([]byte, 1300)
	b[0] = 0x45
	binary.BigEndian.PutUint16(b[2:], uint16(len(b)))
	b[8] = 64
	b[9] = 17
	copy(b[12:16], []byte{10, 8, 1, 1})
	copy(b[16:20], []byte{10, 8, 1, 2})
	return b
}

// TestSteadyPaddingCostsLittle: during a download the client's small packets (TCP ACKs, here pings)
// are padded by random trailers up to half the largest packet seen, on average; the steady content
// padding Camouflage sets instead adds a few bytes.
func TestSteadyPaddingCostsLittle(t *testing.T) {
	avg := func(client string) float64 {
		p := newPair(t, "jc=2\njmin=40\njmax=120\n"+client)
		p.ping(t)
		// The download: big packets towards the client widen what trailers may fill.
		go func() {
			for range p.cliTun.Inbound {
			}
		}()
		for i := 0; i < 20; i++ {
			p.srvTun.Outbound <- bigPacket()
		}
		time.Sleep(200 * time.Millisecond)
		before := len(p.sent())
		const n = 200
		for i := 0; i < n; i++ {
			p.ping(t)
		}
		sent := p.sent()[before:]
		total := 0
		for _, d := range sent {
			total += len(d)
		}
		return float64(total) / float64(len(sent))
	}
	trailers := avg("")
	steady := avg("content_padding_addition=0-31\n")
	t.Logf("small packets during a download: %.0f bytes with random trailers, %.0f with steady padding", trailers, steady)
	if steady*2 > trailers {
		t.Fatalf("steady padding (%.0f bytes) should cost far less than random trailers (%.0f)", steady, trailers)
	}
}

func freePort(t *testing.T) int {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}
