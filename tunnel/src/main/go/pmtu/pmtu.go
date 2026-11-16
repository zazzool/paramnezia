// Package pmtu finds the largest packet a tunnel carries end to end, the way RFC 8899 (packetization
// layer path MTU discovery) does it: by sending probes of the candidate sizes and seeing which come
// back, instead of trusting ICMP "too big" messages that networks often drop.
//
// The probes are ICMP echo requests to the server's tunnel address with Don't Fragment set, put into
// the stream the WireGuard device reads from the TUN, so they travel exactly like app traffic: out
// over the encrypted UDP path, and the server's echo reply back the same way. A size that comes back
// fits the whole path, in both directions, including the server's own interface. Probes can be larger
// than the TUN's current MTU (they never pass through the OS), so a tunnel built with a safe small MTU
// can find out whether a larger one works before it is switched to it.
//
// The hot path costs one atomic load per batch read, and per written packet one byte compare while
// a probe is running.
package pmtu

import (
	"encoding/binary"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// Tun wraps the TUN the WireGuard device reads from.
type Tun struct {
	tun.Device

	pending atomic.Bool // probes waiting to be read by the device
	probing atomic.Bool // replies to watch for

	mu      sync.Mutex
	queue   [][]byte
	id      uint16
	replies map[uint16]bool // seq -> came back
	got     chan struct{}

	// Wake makes the device's blocking Read return, so queued probes go out on an idle tunnel: by
	// default a tiny UDP datagram to the server's tunnel address, which the OS routes into the TUN
	// and this wrapper swallows. Tests replace it.
	Wake func(dst netip.Addr)
}

// New wraps dev.
func New(dev tun.Device) *Tun {
	t := &Tun{Device: dev, id: uint16(time.Now().UnixNano())}
	t.Wake = t.wakeUDP
	return t
}

// wakePort and wakeMagic mark the datagram that wakes the reader; it never leaves the device.
const wakePort = 9

var wakeMagic = []byte("paramnezia-pmtu-wake")

func (t *Tun) wakeUDP(dst netip.Addr) {
	c, err := net.DialUDP("udp4", nil, net.UDPAddrFromAddrPort(netip.AddrPortFrom(dst, wakePort)))
	if err != nil {
		return
	}
	_, _ = c.Write(wakeMagic)
	_ = c.Close()
}

// Probe sends [tries] probes of each size in [sizes] (whole IPv4 packets, bytes) from src to dst
// and returns the largest size that came back within [timeout], or 0 if none did.
func (t *Tun) Probe(src, dst netip.Addr, sizes []int, tries int, timeout time.Duration) int {
	if !src.Is4() || !dst.Is4() || len(sizes) == 0 {
		return 0
	}
	if tries < 1 {
		tries = 1
	}
	t.mu.Lock()
	t.id++
	id := t.id
	t.replies = map[uint16]bool{}
	t.got = make(chan struct{}, 1)
	got := t.got
	t.queue = t.queue[:0]
	for k := 0; k < tries; k++ {
		for i, size := range sizes {
			if size < 68 || size > 65535 {
				continue
			}
			t.queue = append(t.queue, echoRequest(src.As4(), dst.As4(), id, uint16(i<<4|k), size))
		}
	}
	t.mu.Unlock()
	t.probing.Store(true)
	t.pending.Store(true)
	t.Wake(dst)

	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	// Late echoes (the second try of a size that already answered) are still kept from the OS.
	defer time.AfterFunc(timeout, func() {
		t.mu.Lock()
		if t.id == id {
			t.probing.Store(false)
		}
		t.mu.Unlock()
	})
	rewake := time.NewTicker(timeout / 4)
	defer rewake.Stop()
	best := 0
	for {
		select {
		case <-got:
		case <-rewake.C:
			if t.pending.Load() {
				t.Wake(dst)
			}
			continue
		case <-deadline.C:
			return best
		}
		t.mu.Lock()
		all := true
		for i, size := range sizes {
			ok := false
			for k := 0; k < tries; k++ {
				ok = ok || t.replies[uint16(i<<4|k)]
			}
			if ok && size > best {
				best = size
			}
			all = all && ok
		}
		t.mu.Unlock()
		// Sizes are tried largest first in practice: once the largest answered, nothing can beat it.
		if all || (best > 0 && best == maxOf(sizes)) {
			return best
		}
	}
}

func maxOf(s []int) int {
	m := 0
	for _, v := range s {
		if v > m {
			m = v
		}
	}
	return m
}

// Read hands the device what the OS sent, with any queued probes added and the wake datagram removed.
func (t *Tun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if t.pending.Load() {
		if n := t.inject(bufs, sizes, offset, 0); n > 0 {
			return n, nil
		}
	}
	for {
		n, err := t.Device.Read(bufs, sizes, offset)
		if err != nil || n == 0 || !t.pending.Load() && !t.probing.Load() {
			return n, err
		}
		w := 0
		for i := 0; i < n; i++ {
			pkt := bufs[i][offset : offset+sizes[i]]
			if isWake(pkt) {
				continue
			}
			if w != i {
				copy(bufs[w][offset:], pkt)
				sizes[w] = sizes[i]
			}
			w++
		}
		w = t.inject(bufs, sizes, offset, w)
		if w > 0 {
			return w, nil
		}
	}
}

// inject moves queued probes into the free buffers after the first [used]; returns the new count.
func (t *Tun) inject(bufs [][]byte, sizes []int, offset, used int) int {
	t.mu.Lock()
	defer t.mu.Unlock()
	for used < len(bufs) && len(t.queue) > 0 {
		p := t.queue[0]
		if len(bufs[used]) < offset+len(p) {
			// The device's buffers are always large enough (MaxMessageSize); a smaller one is skipped.
			t.queue = t.queue[1:]
			continue
		}
		copy(bufs[used][offset:], p)
		sizes[used] = len(p)
		used++
		t.queue = t.queue[1:]
	}
	if len(t.queue) == 0 {
		t.pending.Store(false)
	}
	return used
}

// Write passes what the device received to the OS, keeping the replies to our probes.
func (t *Tun) Write(bufs [][]byte, offset int) (int, error) {
	if !t.probing.Load() {
		return t.Device.Write(bufs, offset)
	}
	kept := bufs[:0:0]
	for _, b := range bufs {
		if seq, ok := t.reply(b[offset:]); ok {
			t.mu.Lock()
			t.replies[seq] = true
			select {
			case t.got <- struct{}{}:
			default:
			}
			t.mu.Unlock()
			continue
		}
		kept = append(kept, b)
	}
	if len(kept) == 0 {
		return len(bufs), nil
	}
	if _, err := t.Device.Write(kept, offset); err != nil {
		return 0, err
	}
	return len(bufs), nil
}

// reply: an ICMP echo reply to one of our probes; its sequence number.
func (t *Tun) reply(p []byte) (uint16, bool) {
	if len(p) < 28 || p[0]>>4 != 4 || p[9] != 1 {
		return 0, false
	}
	ihl := int(p[0]&0x0f) * 4
	if len(p) < ihl+8 || p[ihl] != 0 {
		return 0, false
	}
	t.mu.Lock()
	id := t.id
	t.mu.Unlock()
	if binary.BigEndian.Uint16(p[ihl+4:]) != id {
		return 0, false
	}
	return binary.BigEndian.Uint16(p[ihl+6:]), true
}

func isWake(p []byte) bool {
	if len(p) < 28 || p[0]>>4 != 4 || p[9] != 17 {
		return false
	}
	ihl := int(p[0]&0x0f) * 4
	if len(p) < ihl+8+len(wakeMagic) || binary.BigEndian.Uint16(p[ihl+2:]) != wakePort {
		return false
	}
	return string(p[ihl+8:ihl+8+len(wakeMagic)]) == string(wakeMagic)
}

// echoRequest builds an IPv4 ICMP echo request of [size] bytes in all, Don't Fragment set.
func echoRequest(src, dst [4]byte, id, seq uint16, size int) []byte {
	p := make([]byte, size)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:], uint16(size))
	binary.BigEndian.PutUint16(p[4:], seq)
	binary.BigEndian.PutUint16(p[6:], 0x4000) // DF
	p[8] = 64
	p[9] = 1
	copy(p[12:16], src[:])
	copy(p[16:20], dst[:])
	binary.BigEndian.PutUint16(p[10:], checksum(p[:20]))
	icmp := p[20:]
	icmp[0] = 8 // echo request
	binary.BigEndian.PutUint16(icmp[4:], id)
	binary.BigEndian.PutUint16(icmp[6:], seq)
	binary.BigEndian.PutUint16(icmp[2:], checksum(icmp))
	return p
}

func checksum(b []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(binary.BigEndian.Uint16(b[i:]))
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s > 0xffff {
		s = s>>16 + s&0xffff
	}
	return ^uint16(s)
}
