/* SPDX-License-Identifier: Apache-2.0 */

package splitapp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// Owner returns the executable path of the process that owns the local end of a flow
// (proto 6 = TCP, 17 = UDP), or "" when it cannot tell.
type Owner func(proto uint8, local, remote netip.AddrPort) string

// Dialer opens the real connections, outside the tunnel.
type Dialer interface {
	DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	ListenUDP(v6 bool) (net.PacketConn, error)
}

type Config struct {
	Rules  *Rules
	Owner  Owner
	Dialer Dialer
	MTU    int
	// Keep are destinations that always stay in the tunnel, whoever sends to them: the tunnel's
	// own subnet (in-tunnel manifests) and its DNS servers.
	Keep []netip.Prefix
	Logf func(format string, args ...any)
}

const (
	writeOffset = 16 // headroom some tun implementations need (amneziawg-go uses the same)
	udpIdle     = 2 * time.Minute
	dnsIdle     = 20 * time.Second
	tcpIdle     = 30 * time.Minute
)

type flowKey struct {
	proto    uint8
	src, dst netip.AddrPort
}

type flow struct {
	direct bool
	seen   time.Time
}

type splitTun struct {
	tun.Device
	cfg    Config
	ep     *channel.Endpoint
	stack  *stack.Stack
	ctx    context.Context
	cancel context.CancelFunc

	mu    sync.Mutex
	flows map[flowKey]*flow

	logged sync.Map // exe -> struct{}: "goes direct" is logged once per program
	direct atomic.Int64
	closed sync.Once
}

// NewTun wraps dev (the packets the system sends into the tunnel). Flows from programs that
// cfg.Rules matches are answered by a user-space stack and relayed through cfg.Dialer; the rest
// reach the caller of Read (amneziawg-go) unchanged.
func NewTun(dev tun.Device, cfg Config) (tun.Device, error) {
	if cfg.Rules.Empty() || cfg.Owner == nil || cfg.Dialer == nil {
		return dev, nil
	}
	mtu := cfg.MTU
	if mtu <= 0 {
		mtu = 1280
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	t := &splitTun{
		Device: dev,
		cfg:    cfg,
		ep:     channel.New(1024, uint32(mtu), ""),
		stack: stack.New(stack.Options{
			NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
			TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		}),
		flows: map[flowKey]*flow{},
	}
	// The packets come from the local Windows stack, which has already checksummed them.
	t.ep.LinkEPCapabilities |= stack.CapabilityRXChecksumOffload
	sack := tcpip.TCPSACKEnabled(true)
	t.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	moderate := tcpip.TCPModerateReceiveBufferOption(true)
	t.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate)
	if err := t.stack.CreateNIC(1, t.ep); err != nil {
		return nil, errors.New("netstack: " + err.String())
	}
	// Accept connections to any address and answer from it: the stack stands in for every
	// destination the excluded programs talk to.
	t.stack.SetPromiscuousMode(1, true)
	t.stack.SetSpoofing(1, true)
	t.stack.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
		{Destination: header.IPv6EmptySubnet, NIC: 1},
	})
	t.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcp.NewForwarder(t.stack, 0, 2048, t.handleTCP).HandlePacket)
	t.stack.SetTransportProtocolHandler(udp.ProtocolNumber, udp.NewForwarder(t.stack, t.handleUDP).HandlePacket)
	t.ctx, t.cancel = context.WithCancel(context.Background())
	go t.toSystem()
	go t.sweep()
	return t, nil
}

// Read passes on the packets for the tunnel and hands the excluded programs' ones to the stack.
func (t *splitTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	for {
		n, err := t.Device.Read(bufs, sizes, offset)
		if n == 0 || err != nil {
			return n, err
		}
		w := 0
		for i := 0; i < n; i++ {
			pkt := bufs[i][offset : offset+sizes[i]]
			if t.isDirect(pkt) {
				t.inject(pkt)
				continue
			}
			if w != i {
				copy(bufs[w][offset:], pkt)
				sizes[w] = sizes[i]
			}
			w++
		}
		if w > 0 {
			return w, nil
		}
	}
}

func (t *splitTun) Close() error {
	t.closed.Do(func() {
		t.cancel()
		t.stack.Close()
		t.ep.Close()
	})
	return t.Device.Close()
}

// DirectFlows counts the connections sent past the tunnel so far.
func (t *splitTun) DirectFlows() int64 { return t.direct.Load() }

func (t *splitTun) isDirect(pkt []byte) bool {
	k, flags, ok := parseFlow(pkt)
	if !ok {
		return false
	}
	for _, p := range t.cfg.Keep {
		if p.Contains(k.dst.Addr()) {
			return false
		}
	}
	now := time.Now()
	t.mu.Lock()
	f := t.flows[k]
	if f != nil {
		f.seen = now
	}
	t.mu.Unlock()
	if k.proto == 6 {
		// A new connection (SYN) is classified now; anything else follows its connection's choice,
		// and connections the stack has never seen (opened before the tunnel came up) stay put.
		if flags&tcpSYN == 0 || flags&tcpACK != 0 {
			return f != nil && f.direct
		}
	} else if f != nil {
		return f.direct
	}
	direct := t.classify(k)
	t.mu.Lock()
	if direct || k.proto == 17 {
		t.flows[k] = &flow{direct: direct, seen: now}
	} else {
		delete(t.flows, k)
	}
	t.mu.Unlock()
	return direct
}

func (t *splitTun) classify(k flowKey) bool {
	exe := t.cfg.Owner(k.proto, k.src, k.dst)
	if !t.cfg.Rules.Match(exe) {
		return false
	}
	t.direct.Add(1)
	if _, seen := t.logged.LoadOrStore(exe, struct{}{}); !seen {
		t.cfg.Logf("split: %s goes past the VPN", exe)
	}
	return true
}

func (t *splitTun) inject(pkt []byte) {
	proto := header.IPv4ProtocolNumber
	if pkt[0]>>4 == 6 {
		proto = header.IPv6ProtocolNumber
	}
	pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(pkt)})
	t.ep.InjectInbound(proto, pb)
	pb.DecRef()
}

// toSystem writes the stack's answers back to Windows, as if they came out of the tunnel.
func (t *splitTun) toSystem() {
	for {
		pb := t.ep.ReadContext(t.ctx)
		if pb == nil {
			return
		}
		view := pb.ToView()
		pb.DecRef()
		buf := make([]byte, writeOffset+view.Size())
		copy(buf[writeOffset:], view.AsSlice())
		view.Release()
		if _, err := t.Device.Write([][]byte{buf}, writeOffset); err != nil && t.ctx.Err() != nil {
			return
		}
	}
}

func (t *splitTun) sweep() {
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-t.ctx.Done():
			return
		case now := <-tick.C:
			t.mu.Lock()
			for k, f := range t.flows {
				idle := udpIdle
				if k.proto == 6 {
					idle = tcpIdle
				}
				if now.Sub(f.seen) > idle {
					delete(t.flows, k)
				}
			}
			t.mu.Unlock()
		}
	}
}

func (t *splitTun) handleTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := netip.AddrPortFrom(toAddr(id.LocalAddress), id.LocalPort)
	ctx, cancel := context.WithTimeout(t.ctx, 15*time.Second)
	out, err := t.cfg.Dialer.DialTCP(ctx, dst)
	cancel()
	if err != nil {
		r.Complete(true) // the program sees "connection refused", as it would without a VPN
		return
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		r.Complete(true)
		out.Close()
		return
	}
	r.Complete(false)
	ep.SocketOptions().SetKeepAlive(true)
	in := gonet.NewTCPConn(&wq, ep)
	splice(in, out)
}

type closeWriter interface{ CloseWrite() error }

func splice(a, b net.Conn) {
	done := make(chan struct{}, 2)
	pump := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(closeWriter); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
		done <- struct{}{}
	}
	go pump(a, b)
	go pump(b, a)
	<-done
	// One side finished; give the other a moment to drain, then close both.
	select {
	case <-done:
	case <-time.After(2 * time.Minute):
	}
	a.Close()
	b.Close()
}

// handleUDP takes a new UDP flow; false (no endpoint) lets the stack answer "port unreachable".
func (t *splitTun) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := netip.AddrPortFrom(toAddr(id.LocalAddress), id.LocalPort)
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		return false
	}
	in := gonet.NewUDPConn(&wq, ep)
	go t.relayUDP(in, dst)
	return true
}

func (t *splitTun) relayUDP(in *gonet.UDPConn, dst netip.AddrPort) {
	defer in.Close()
	out, err := t.cfg.Dialer.ListenUDP(dst.Addr().Is6())
	if err != nil {
		return
	}
	defer out.Close()
	idle := udpIdle
	if dst.Port() == 53 {
		idle = dnsIdle
	}
	target := net.UDPAddrFromAddrPort(dst)
	var last atomic.Int64
	touch := func() { last.Store(time.Now().UnixNano()) }
	touch()
	go func() {
		buf := make([]byte, 65535)
		for {
			n, from, err := out.ReadFrom(buf)
			if err != nil {
				in.Close()
				return
			}
			// Only the destination may answer: the stack can speak only for that address here.
			if ua, ok := from.(*net.UDPAddr); !ok || ua.AddrPort().Addr().Unmap() != dst.Addr().Unmap() || ua.AddrPort().Port() != dst.Port() {
				continue
			}
			touch()
			if _, err := in.Write(buf[:n]); err != nil {
				out.Close()
				return
			}
		}
	}()
	buf := make([]byte, 65535)
	for {
		_ = in.SetReadDeadline(time.Now().Add(idle))
		n, err := in.Read(buf)
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() && time.Since(time.Unix(0, last.Load())) < idle {
				continue // the answers keep the flow alive too
			}
			return
		}
		touch()
		if _, err := out.WriteTo(buf[:n], target); err != nil {
			return
		}
	}
}

func toAddr(a tcpip.Address) netip.Addr {
	if a.Len() == 4 {
		return netip.AddrFrom4(a.As4())
	}
	return netip.AddrFrom16(a.As16())
}
