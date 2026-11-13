/* SPDX-License-Identifier: Apache-2.0 */

// Package dnsfilter is the split DNS with tracker blocking that runs inside the tun device, shared by
// the Android library and the Windows service.
package dnsfilter

import (
	"encoding/binary"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// Split DNS with tracker blocking, done where packets already pass: the tun device.
//
// The VPN announces one virtual resolver address. Queries to it never reach the network as-is:
//   - ad, tracking and telemetry names get an empty answer right here (0.0.0.0 / ::);
//   - local names (nas.lan, printer.local, a bare "nas", the router's own sites such as
//     my.keenetic.net, reverse lookups of private addresses) go to the resolver of the local network,
//     the router, over the same direct socket: the local network is never in the tunnel, and only
//     the router knows its own names;
//   - Russian names go to a Russian resolver over a socket the app protected from the VPN, so
//     Russian services see a Russian resolver and answer with Russian addresses;
//   - everything else goes to the tunnel's own DNS server, through the tunnel.
// Every other packet passes untouched; the only per-packet cost is a few byte compares.

const (
	ruleBlock uint8 = 1 << iota
	ruleDirect
	ruleAllow
)

type dnsRules struct {
	suffix map[string]uint8
	exact  map[string]uint8
}

var (
	rules        atomic.Pointer[dnsRules]
	blockedCount atomic.Int64
	localServers atomic.Pointer[[]*net.UDPAddr]
	localGen     atomic.Int64 // bumped with the local servers, so cached local answers expire
)

// localSuffixes are names that only the local network can answer: home-network TLDs, names routers
// answer for their own admin pages, and the reverse zones of private and link-local addresses.
var localSuffixes = func() map[string]bool {
	m := map[string]bool{}
	for _, s := range strings.Fields(`
		local lan home home.arpa internal localdomain intranet private corp router gateway
		fritz.box speedport.ip my.keenetic.net tplinkwifi.net tplinklogin.net tplinkmodem.net
		routerlogin.net routerlogin.com mywifiext.net router.asus.com asusrouter.com miwifi.com
		10.in-addr.arpa 168.192.in-addr.arpa 254.169.in-addr.arpa
		d.f.ip6.arpa c.f.ip6.arpa 8.e.f.ip6.arpa 9.e.f.ip6.arpa a.e.f.ip6.arpa b.e.f.ip6.arpa`) {
		m[s] = true
	}
	for i := 16; i < 32; i++ {
		m[strconv.Itoa(i)+".172.in-addr.arpa"] = true
	}
	return m
}()

// isLocalName: a single label ("nas") or a name under one of localSuffixes.
func isLocalName(name string) bool {
	if name == "" {
		return false
	}
	if !strings.Contains(name, ".") {
		return true
	}
	for {
		if localSuffixes[name] {
			return true
		}
		i := strings.IndexByte(name, '.')
		if i < 0 {
			return false
		}
		name = name[i+1:]
	}
}

// SetLocalServers sets the resolvers of the local network (the router), IPv4 only, for all
// tunnels; empty when the device is not on a home network. Effective for the next query.
func SetLocalServers(servers []string) {
	list := parseServers(servers, true)
	localServers.Store(&list)
	localGen.Add(1)
}

func currentLocalServers() []*net.UDPAddr {
	if p := localServers.Load(); p != nil {
		return *p
	}
	return nil
}

func addRules(r *dnsRules, text string, flag uint8) {
	for _, line := range strings.Split(text, "\n") {
		line = strings.ToLower(strings.TrimSpace(line))
		if line == "" || line[0] == '#' {
			continue
		}
		if strings.HasPrefix(line, "full:") {
			r.exact[strings.TrimSuffix(line[5:], ".")] |= flag
		} else {
			r.suffix[strings.TrimSuffix(strings.TrimPrefix(line, "domain:"), ".")] |= flag
		}
	}
}

func parseRules(block, direct, allow string) *dnsRules {
	r := &dnsRules{suffix: map[string]uint8{}, exact: map[string]uint8{}}
	addRules(r, block, ruleBlock)
	addRules(r, direct, ruleDirect)
	addRules(r, allow, ruleAllow)
	return r
}

// classify returns the combined rule bits for name: exact rules plus every suffix of the name.
func (r *dnsRules) classify(name string) uint8 {
	if r == nil {
		return 0
	}
	f := r.exact[name]
	for {
		f |= r.suffix[name]
		i := strings.IndexByte(name, '.')
		if i < 0 {
			return f
		}
		name = name[i+1:]
	}
}

// --- upstream forwarding ----------------------------------------------------------------------

type pending struct {
	origID uint16
	ch     chan []byte
}

// forwarder multiplexes all queries for one route (direct or tunnel) over a single UDP socket,
// so no socket is created per query and the radio sees as few packets as possible.
type forwarder struct {
	conn    *net.UDPConn
	servers []*net.UDPAddr
	mu      sync.Mutex
	waiting map[uint16]*pending
	nextID  uint16
}

func newForwarder(conn *net.UDPConn, servers []string) *forwarder {
	f := &forwarder{conn: conn, waiting: map[uint16]*pending{}, nextID: uint16(time.Now().UnixNano())}
	f.servers = parseServers(servers, false)
	go f.readLoop()
	return f
}

// parseServers reads "ip" or "ip:port" entries; onlyV4 drops IPv6 ones (the sockets are udp4).
func parseServers(servers []string, onlyV4 bool) []*net.UDPAddr {
	var out []*net.UDPAddr
	for _, s := range servers {
		s = strings.TrimSpace(s)
		var ap netip.AddrPort
		if p, err := netip.ParseAddrPort(s); err == nil {
			ap = p
		} else if a, err := netip.ParseAddr(s); err == nil {
			ap = netip.AddrPortFrom(a, 53)
		} else {
			continue
		}
		ap = netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())
		if onlyV4 && !ap.Addr().Is4() {
			continue
		}
		out = append(out, net.UDPAddrFromAddrPort(ap))
	}
	return out
}

func (f *forwarder) readLoop() {
	buf := make([]byte, 4096)
	for {
		n, _, err := f.conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 12 {
			continue
		}
		id := binary.BigEndian.Uint16(buf)
		f.mu.Lock()
		p := f.waiting[id]
		delete(f.waiting, id)
		f.mu.Unlock()
		if p != nil {
			resp := append([]byte(nil), buf[:n]...)
			binary.BigEndian.PutUint16(resp, p.origID)
			p.ch <- resp
		}
	}
}

// query sends q to the first server, then to the next ones if it stays silent.
func (f *forwarder) query(q []byte) []byte { return f.queryTo(q, f.servers) }

// queryTo is query with another server list over the same socket.
func (f *forwarder) queryTo(q []byte, servers []*net.UDPAddr) []byte {
	if len(servers) == 0 {
		return nil
	}
	p := &pending{origID: binary.BigEndian.Uint16(q), ch: make(chan []byte, 1)}
	f.mu.Lock()
	f.nextID++
	id := f.nextID
	f.waiting[id] = p
	f.mu.Unlock()
	defer func() { f.mu.Lock(); delete(f.waiting, id); f.mu.Unlock() }()

	out := append([]byte(nil), q...)
	binary.BigEndian.PutUint16(out, id)
	deadline := time.After(4 * time.Second)
	for i := 0; ; i++ {
		if i < len(servers) {
			f.conn.WriteToUDP(out, servers[i])
		}
		select {
		case r := <-p.ch:
			return r
		case <-deadline:
			return nil
		case <-time.After(1200 * time.Millisecond):
		}
	}
}

func (f *forwarder) close() { f.conn.Close() }

// --- answer cache -----------------------------------------------------------------------------

// A small cache saves radio wake-ups: apps resolve the same few names over and over.
//
//   - Bounded without a cliff: when full, one entry goes, chosen by sampling a few (Go's map order
//     is random) and dropping the least recently used of them: approximate LRU in O(1), the way
//     Redis does it. The old cache emptied itself completely at 1024 names, and every app then
//     re-resolved at once.
//   - Serve-stale (RFC 8767): an answer is kept for staleFor after its TTL. A query that misses goes
//     upstream as usual, and only if no resolver answers is the stale answer returned (with a short
//     TTL). Sites keep opening through the seconds the tunnel needs to repair itself (fresh sockets,
//     a new network), instead of failing with "name not found".
//   - Prefetch: a hit in the last tenth of an answer's life is answered from the cache and refreshed
//     in the background, so names in use never expire under the user.
type cached struct {
	resp     []byte
	stored   time.Time
	expires  time.Time
	used     time.Time
	fetching bool
}

const (
	cacheSize  = 1024
	staleFor   = 6 * time.Hour
	staleTTL   = 30 // seconds, on a stale answer
	evictProbe = 8
)

type dnsCache struct {
	mu  sync.Mutex
	m   map[string]*cached
	now func() time.Time
}

func newDNSCache() dnsCache { return dnsCache{m: map[string]*cached{}, now: time.Now} }

// lookup returns a copy of the cached answer with id, whether it is still fresh, and whether the
// caller should refresh it in the background (a fresh answer close to expiry, not already being
// refreshed). A nil answer means a miss, or an entry too old even to serve stale.
func (c *dnsCache) lookup(key string, id uint16) (resp []byte, fresh, prefetch bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[key]
	if !ok {
		return nil, false, false
	}
	now := c.now()
	if now.After(e.expires.Add(staleFor)) {
		delete(c.m, key)
		return nil, false, false
	}
	e.used = now
	r := append([]byte(nil), e.resp...)
	binary.BigEndian.PutUint16(r, id)
	fresh = !now.After(e.expires)
	if fresh {
		left, life := e.expires.Sub(now), e.expires.Sub(e.stored)
		if !e.fetching && left*10 < life {
			e.fetching, prefetch = true, true
		}
		setTTL(r, int(left/time.Second)) // the remaining life, as a resolver would say
	} else {
		setTTL(r, staleTTL)
	}
	return r, fresh, prefetch
}

// done clears the background-refresh mark when a refresh gave nothing.
func (c *dnsCache) done(key string) {
	c.mu.Lock()
	if e := c.m[key]; e != nil {
		e.fetching = false
	}
	c.mu.Unlock()
}

func (c *dnsCache) put(key string, resp []byte) {
	ttl := minTTL(resp)
	if ttl <= 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if _, ok := c.m[key]; !ok && len(c.m) >= cacheSize {
		c.evictLocked(now)
	}
	c.m[key] = &cached{resp: resp, stored: now, expires: now.Add(time.Duration(ttl) * time.Second), used: now}
}

func (c *dnsCache) evictLocked(now time.Time) {
	var victim string
	var oldest time.Time
	n := 0
	for k, e := range c.m {
		if now.After(e.expires.Add(staleFor)) {
			victim = k
			break
		}
		if n == 0 || e.used.Before(oldest) {
			victim, oldest = k, e.used
		}
		if n++; n >= evictProbe {
			break
		}
	}
	delete(c.m, victim)
}

// setTTL rewrites the TTL of every answer, authority and additional record (OPT excepted).
func setTTL(m []byte, ttl int) {
	if ttl < 1 {
		ttl = 1
	}
	if len(m) < 12 {
		return
	}
	qd := int(binary.BigEndian.Uint16(m[4:]))
	rr := int(binary.BigEndian.Uint16(m[6:])) + int(binary.BigEndian.Uint16(m[8:])) + int(binary.BigEndian.Uint16(m[10:]))
	i := 12
	for ; qd > 0; qd-- {
		if i = skipName(m, i); i < 0 || i+4 > len(m) {
			return
		}
		i += 4
	}
	for ; rr > 0; rr-- {
		if i = skipName(m, i); i < 0 || i+10 > len(m) {
			return
		}
		if binary.BigEndian.Uint16(m[i:]) != 41 { // OPT keeps its flags in the TTL field
			binary.BigEndian.PutUint32(m[i+4:], uint32(ttl))
		}
		i += 10 + int(binary.BigEndian.Uint16(m[i+8:]))
	}
}

// --- tun wrapper ------------------------------------------------------------------------------

// Config says where the virtual resolver lives and which resolvers answer behind it.
type Config = dnsConfig

type dnsConfig struct {
	virtual netip.Addr
	tunnel  []string
	direct  []string
}

// ParseConfig reads "virtual=…\ntunnel=a,b\ndirect=c,d"; false if there is no IPv4 virtual address.
func ParseConfig(s string) (Config, bool) {
	var c dnsConfig
	for _, line := range strings.Split(s, "\n") {
		k, v, _ := strings.Cut(strings.TrimSpace(line), "=")
		var list []string
		for _, x := range strings.Split(v, ",") {
			if x = strings.TrimSpace(x); x != "" {
				list = append(list, x)
			}
		}
		switch k {
		case "virtual":
			c.virtual, _ = netip.ParseAddr(v)
		case "tunnel":
			c.tunnel = list
		case "direct":
			c.direct = list
		}
	}
	return c, c.virtual.Is4()
}

type dnsTun struct {
	tun.Device
	virtual [4]byte
	tunnel  *forwarder
	direct  *forwarder
	cache   dnsCache
	slots   chan struct{}
	writeMu sync.Mutex
}

// NewTun wraps dev. direct is a UDP socket that bypasses the VPN (Android: protected by the app;
// Windows: the direct resolvers are routed outside the tunnel), or nil to resolve everything in it.
func NewTun(dev tun.Device, cfg Config, direct *net.UDPConn) (tun.Device, error) {
	tunnelConn, err := net.ListenUDP("udp4", nil)
	if err != nil {
		return nil, err
	}
	t := &dnsTun{
		Device:  dev,
		virtual: cfg.virtual.As4(),
		tunnel:  newForwarder(tunnelConn, cfg.tunnel),
		cache:   newDNSCache(),
		slots:   make(chan struct{}, 64),
	}
	if direct != nil {
		t.direct = newForwarder(direct, cfg.direct)
	}
	return t, nil
}

func (t *dnsTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	for {
		n, err := t.Device.Read(bufs, sizes, offset)
		if n == 0 || err != nil {
			return n, err
		}
		w := 0
		for i := 0; i < n; i++ {
			pkt := bufs[i][offset : offset+sizes[i]]
			if t.isQuery(pkt) {
				t.handle(append([]byte(nil), pkt...))
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

func (t *dnsTun) Close() error {
	t.tunnel.close()
	if t.direct != nil {
		t.direct.close()
	}
	return t.Device.Close()
}

// isQuery: IPv4, UDP, to the virtual resolver on port 53, not fragmented.
func (t *dnsTun) isQuery(p []byte) bool {
	if len(p) < 28 || p[0]>>4 != 4 || p[9] != 17 {
		return false
	}
	if p[16] != t.virtual[0] || p[17] != t.virtual[1] || p[18] != t.virtual[2] || p[19] != t.virtual[3] {
		return false
	}
	if binary.BigEndian.Uint16(p[6:])&0x3fff != 0 {
		return false
	}
	ihl := int(p[0]&0x0f) * 4
	return len(p) >= ihl+8+12 && binary.BigEndian.Uint16(p[ihl+2:]) == 53
}

func (t *dnsTun) handle(pkt []byte) {
	ihl := int(pkt[0]&0x0f) * 4
	var client [4]byte
	copy(client[:], pkt[12:16])
	clientPort := binary.BigEndian.Uint16(pkt[ihl:])
	q := pkt[ihl+8:]
	name, qtype, qend, ok := parseQuestion(q)
	if !ok {
		return
	}
	flags := rules.Load().classify(name)
	if flags&ruleBlock != 0 && flags&ruleAllow == 0 {
		blockedCount.Add(1)
		t.reply(client, clientPort, blockedAnswer(q, qtype, qend))
		return
	}
	fw, route := t.tunnel, "t"
	var servers []*net.UDPAddr
	if t.direct != nil && isLocalName(name) {
		if servers = currentLocalServers(); len(servers) > 0 {
			fw, route = t.direct, "l"+strconv.FormatInt(localGen.Load(), 36)
		}
	}
	if fw == t.tunnel && flags&ruleDirect != 0 && t.direct != nil {
		fw, route = t.direct, "d"
	}
	key := route + name + "/" + string(rune(qtype))
	id := binary.BigEndian.Uint16(q)
	cachedResp, fresh, prefetch := t.cache.lookup(key, id)
	if fresh {
		t.reply(client, clientPort, cachedResp)
		if !prefetch {
			return
		}
	}
	select {
	case t.slots <- struct{}{}:
	default:
		if cachedResp != nil && !fresh {
			t.reply(client, clientPort, cachedResp) // overloaded: an old answer beats none
		}
		if prefetch {
			t.cache.done(key)
		}
		return // overloaded: drop, the app retries
	}
	go func() {
		defer func() { <-t.slots }()
		var r []byte
		if servers != nil && fw == t.direct && route[0] == 'l' {
			r = fw.queryTo(q, servers)
		} else {
			r = fw.query(q)
		}
		if r == nil && fw == t.direct {
			r = t.tunnel.query(q) // Russian resolver unreachable: still answer
		}
		switch {
		case r != nil:
			t.cache.put(key, r)
			if !fresh {
				t.reply(client, clientPort, r)
			}
		case prefetch:
			t.cache.done(key)
		case cachedResp != nil:
			t.reply(client, clientPort, cachedResp) // no resolver answered: serve stale (RFC 8767)
		}
	}()
}

// reply writes a DNS answer back to the app as an IPv4/UDP packet from the virtual resolver.
func (t *dnsTun) reply(client [4]byte, port uint16, payload []byte) {
	const off = 16 // headroom some tun implementations need for their own headers
	buf := make([]byte, off+28+len(payload))
	p := buf[off:]
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:], uint16(len(p)))
	p[8] = 64
	p[9] = 17
	copy(p[12:16], t.virtual[:])
	copy(p[16:20], client[:])
	binary.BigEndian.PutUint16(p[10:], ipChecksum(p[:20]))
	binary.BigEndian.PutUint16(p[20:], 53)
	binary.BigEndian.PutUint16(p[22:], port)
	binary.BigEndian.PutUint16(p[24:], uint16(8+len(payload)))
	// UDP checksum 0 = none, which IPv4 allows.
	copy(p[28:], payload)
	t.writeMu.Lock()
	t.Device.Write([][]byte{buf}, off)
	t.writeMu.Unlock()
}

func ipChecksum(h []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(h); i += 2 {
		s += uint32(binary.BigEndian.Uint16(h[i:]))
	}
	for s > 0xffff {
		s = s>>16 + s&0xffff
	}
	return ^uint16(s)
}

// --- DNS message helpers ------------------------------------------------------------------------

// parseQuestion returns the lowercase first question name, its type and where the question ends.
func parseQuestion(m []byte) (string, uint16, int, bool) {
	if len(m) < 12 || m[2]&0x80 != 0 || binary.BigEndian.Uint16(m[4:]) == 0 {
		return "", 0, 0, false
	}
	var b strings.Builder
	i := 12
	for {
		if i >= len(m) {
			return "", 0, 0, false
		}
		l := int(m[i])
		if l == 0 {
			i++
			break
		}
		if l&0xc0 != 0 || i+1+l > len(m) {
			return "", 0, 0, false
		}
		if b.Len() > 0 {
			b.WriteByte('.')
		}
		for _, c := range m[i+1 : i+1+l] {
			if c >= 'A' && c <= 'Z' {
				c += 'a' - 'A'
			}
			b.WriteByte(c)
		}
		i += 1 + l
	}
	if i+4 > len(m) {
		return "", 0, 0, false
	}
	return b.String(), binary.BigEndian.Uint16(m[i:]), i + 4, true
}

// blockedAnswer: 0.0.0.0 / :: for A / AAAA, an empty NOERROR answer for anything else. Apps give up
// at once instead of retrying, which an NXDOMAIN or a timeout would not guarantee.
func blockedAnswer(q []byte, qtype uint16, qend int) []byte {
	r := append([]byte(nil), q[:qend]...)
	r[2] = 0x80 | (q[2] & 0x01) // QR, keep RD
	r[3] = 0x80                 // RA, NOERROR
	binary.BigEndian.PutUint16(r[4:], 1)
	binary.BigEndian.PutUint16(r[6:], 0)
	binary.BigEndian.PutUint16(r[8:], 0)
	binary.BigEndian.PutUint16(r[10:], 0)
	var rdata []byte
	switch qtype {
	case 1:
		rdata = make([]byte, 4)
	case 28:
		rdata = make([]byte, 16)
	default:
		return r
	}
	binary.BigEndian.PutUint16(r[6:], 1)
	rr := []byte{0xc0, 0x0c, byte(qtype >> 8), byte(qtype), 0, 1, 0, 0, 0x0e, 0x10, 0, byte(len(rdata))}
	return append(append(r, rr...), rdata...)
}

// minTTL of the answer records, clamped to [10s, 10min]; 0 means "do not cache".
func minTTL(m []byte) int {
	if len(m) < 12 || m[3]&0x0f != 0 || m[2]&0x02 != 0 {
		return 0 // error or truncated
	}
	qd := int(binary.BigEndian.Uint16(m[4:]))
	an := int(binary.BigEndian.Uint16(m[6:]))
	if an == 0 {
		return 0
	}
	i := 12
	for ; qd > 0; qd-- {
		if i = skipName(m, i); i < 0 || i+4 > len(m) {
			return 0
		}
		i += 4
	}
	ttl := 600
	for ; an > 0; an-- {
		if i = skipName(m, i); i < 0 || i+10 > len(m) {
			return 0
		}
		if t := int(binary.BigEndian.Uint32(m[i+4:])); t < ttl {
			ttl = t
		}
		i += 10 + int(binary.BigEndian.Uint16(m[i+8:]))
	}
	if ttl < 10 {
		return 0
	}
	return ttl
}

func skipName(m []byte, i int) int {
	for i < len(m) {
		l := int(m[i])
		switch {
		case l == 0:
			return i + 1
		case l&0xc0 == 0xc0:
			return i + 2
		default:
			i += 1 + l
		}
	}
	return -1
}

// SetRules replaces the block / direct / allow lists (one rule per line, `full:` for exact names)
// for all tunnels, effective for the next query.
func SetRules(block, direct, allow string) {
	rules.Store(parseRules(block, direct, allow))
}

// Blocked returns how many queries were blocked since the process started.
func Blocked() int64 { return blockedCount.Load() }
