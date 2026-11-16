package pmtu

import (
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/tuntest"
	"golang.org/x/crypto/curve25519"
)

func key() (priv, pub string) {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	b[0] &= 248
	b[31] = (b[31] & 127) | 64
	pk, _ := curve25519.X25519(b, curve25519.Basepoint)
	return hex.EncodeToString(b), hex.EncodeToString(pk)
}

func freePort(t *testing.T) int {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// narrowPath relays between client and server and drops datagrams larger than limit, like a link
// with a small MTU that does not tell anyone.
func narrowPath(t *testing.T, ln *net.UDPConn, server *net.UDPAddr, limit int) {
	up, err := net.DialUDP("udp4", nil, server)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { up.Close() })
	var client *net.UDPAddr
	clientSet := make(chan struct{})
	go func() {
		<-clientSet
		buf := make([]byte, 65536)
		for {
			n, err := up.Read(buf)
			if err != nil {
				return
			}
			if n <= limit {
				_, _ = ln.WriteToUDP(buf[:n], client)
			}
		}
	}()
	go func() {
		buf := make([]byte, 65536)
		for {
			n, from, err := ln.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if client == nil {
				client = from
				close(clientSet)
			}
			if n <= limit {
				_, _ = up.Write(buf[:n])
			}
		}
	}()
}

// echoServer plays the server's OS: answers ICMP echo requests up to its interface MTU.
func echoServer(srv *tuntest.ChannelTUN, mtu int) {
	go func() {
		for p := range srv.Inbound {
			if len(p) < 28 || p[9] != 1 || p[20] != 8 || len(p) > mtu {
				continue
			}
			r := append([]byte(nil), p...)
			copy(r[12:16], p[16:20])
			copy(r[16:20], p[12:16])
			r[10], r[11] = 0, 0
			binary.BigEndian.PutUint16(r[10:], checksum(r[:20]))
			r[20] = 0
			r[22], r[23] = 0, 0
			binary.BigEndian.PutUint16(r[22:], checksum(r[20:]))
			srv.Outbound <- r
		}
	}()
}

func pair(t *testing.T, pathLimit, serverMTU int) (*Tun, *tuntest.ChannelTUN) {
	srvPriv, srvPub := key()
	cliPriv, cliPub := key()
	srvTun := tuntest.NewChannelTUN()
	srv := device.NewDevice(srvTun.TUN(), conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "srv "))
	t.Cleanup(srv.Close)
	port := freePort(t)
	shared := "s1=12\ns2=12\ns3=12\ns4=12\nh1=1\nh2=2\nh3=3\nh4=4\nheader_protection_key=" + strings.Repeat("cd", 32) + "\n"
	if err := srv.IpcSet("private_key=" + srvPriv + "\nlisten_port=" + fmt.Sprint(port) + "\n" + shared +
		"public_key=" + cliPub + "\nallowed_ip=10.8.1.2/32\n"); err != nil {
		t.Fatal(err)
	}
	if err := srv.Up(); err != nil {
		t.Fatal(err)
	}
	echoServer(srvTun, serverMTU)

	ln, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	narrowPath(t, ln, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}, pathLimit)

	cliTun := tuntest.NewChannelTUN()
	probe := New(cliTun.TUN())
	// The OS side: the wake datagram arrives from the TUN like any app packet.
	probe.Wake = func(dst netip.Addr) {
		p := make([]byte, 28+len(wakeMagic))
		p[0] = 0x45
		binary.BigEndian.PutUint16(p[2:], uint16(len(p)))
		p[9] = 17
		copy(p[12:16], []byte{10, 8, 1, 2})
		d := dst.As4()
		copy(p[16:20], d[:])
		binary.BigEndian.PutUint16(p[22:], wakePort)
		copy(p[28:], wakeMagic)
		go func() { cliTun.Outbound <- p }()
	}
	cli := device.NewDevice(probe, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "cli "))
	t.Cleanup(cli.Close)
	if err := cli.IpcSet("private_key=" + cliPriv + "\n" + shared + "public_key=" + srvPub +
		"\nendpoint=" + ln.LocalAddr().String() + "\nallowed_ip=10.8.1.0/24\n"); err != nil {
		t.Fatal(err)
	}
	if err := cli.Up(); err != nil {
		t.Fatal(err)
	}
	// The app probes a tunnel it has already verified: wait for one small echo to come back first.
	go func() {
		for p := range cliTun.Inbound {
			// (A warm-up echo answered after its probe gave up may arrive; the sized probes may not.)
			if len(p) > 1000 && p[9] == 1 && p[20] == 0 {
				t.Errorf("a probe reply reached the OS")
			}
		}
	}()
	for i := 0; probe.Probe(self, server, []int{100}, 1, 2*time.Second) == 0; i++ {
		if i == 10 {
			t.Fatal("the tunnel never came up")
		}
	}
	return probe, cliTun
}

var (
	self   = netip.MustParseAddr("10.8.1.2")
	server = netip.MustParseAddr("10.8.1.1")
	sizes  = []int{1420, 1400, 1360, 1320, 1280}
)

func TestProbeFindsWhatThePathCarries(t *testing.T) {
	for _, tc := range []struct {
		name            string
		path, serverMTU int
		want            int
	}{
		// WireGuard adds 32 bytes, S4 12 more, and pads to 16: 1420 → 1464 on the wire.
		{"wide path", 1500, 1420, 1420},
		{"narrow path", 1420, 1420, 1360},
		{"server interface smaller", 1500, 1370, 1360},
		{"nothing above the minimum", 1300, 1420, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			// Whatever the OS writes back keeps flowing (pair drains it); our replies must not reach it.
			probe, _ := pair(t, tc.path, tc.serverMTU)
			got := probe.Probe(self, server, sizes, 2, 3*time.Second)
			if got != tc.want {
				t.Fatalf("got %d, want %d", got, tc.want)
			}
		})
	}
}
