//go:build windows

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"strconv"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// directProxy is the Windows counterpart of Android's "bind to the physical network": a SOCKS5
// proxy on 127.0.0.1, with a random user name and password only the app learns over the pipe.
// Its connections leave over the default physical interface (IP_UNICAST_IF), past the tunnel.
// The app uses it for what must work while its server is blocked: the fleet's reserve channel,
// server management and "what do Russian sites see". It only connects out (CONNECT), never listens
// beyond loopback.
type directProxy struct {
	ln         net.Listener
	addr       string
	user, pass string
	ours       func() winipcfg.LUID
	resolver   *net.Resolver
}

// Names are resolved over the physical network too: the system resolver goes through the tunnel,
// which may be the thing that is down.
var directResolvers = []string{"77.88.8.8:53", "1.1.1.1:53", "8.8.8.8:53"}

func startDirectProxy(ours func() winipcfg.LUID) (*directProxy, error) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	p := &directProxy{ln: ln, addr: ln.Addr().String(), user: randomHex(8), pass: randomHex(16), ours: ours}
	p.resolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var last error
		for _, r := range directResolvers {
			c, err := p.dialer(3*time.Second).DialContext(ctx, network, r)
			if err == nil {
				return c, nil
			}
			last = err
		}
		return nil, last
	}}
	go p.serve()
	return p, nil
}

func (p *directProxy) close() { p.ln.Close() }

func (p *directProxy) serve() {
	for {
		c, err := p.ln.Accept()
		if err != nil {
			return
		}
		go p.handle(c)
	}
}

func (p *directProxy) dialer(timeout time.Duration) *net.Dialer {
	return &net.Dialer{Timeout: timeout, Control: func(network, address string, rc syscall.RawConn) error {
		v6 := network == "tcp6" || network == "udp6"
		family := winipcfg.AddressFamily(windows.AF_INET)
		if v6 {
			family = windows.AF_INET6
		}
		index, err := defaultInterface(family, p.ours())
		if err != nil {
			return nil // no physical default route: let the system decide
		}
		return bindToInterface(rc, v6, index)
	}}
}

func (p *directProxy) handle(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(30 * time.Second))
	host, port, err := p.handshake(c)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	addrs := []string{host}
	if net.ParseIP(host) == nil {
		ips, err := p.resolver.LookupHost(ctx, host)
		if err != nil || len(ips) == 0 {
			reply(c, 4) // host unreachable
			return
		}
		addrs = ips
	}
	var out net.Conn
	for _, a := range addrs {
		network := "tcp4"
		if ip := net.ParseIP(a); ip != nil && ip.To4() == nil {
			network = "tcp6"
		}
		if out, err = p.dialer(15*time.Second).DialContext(ctx, network, net.JoinHostPort(a, strconv.Itoa(port))); err == nil {
			break
		}
	}
	if out == nil {
		reply(c, 5) // connection refused
		return
	}
	defer out.Close()
	if reply(c, 0) != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(out, c); closeWrite(out); done <- struct{}{} }()
	go func() { _, _ = io.Copy(c, out); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if t, ok := c.(*net.TCPConn); ok {
		_ = t.CloseWrite()
	}
}

// handshake does RFC 1928 with RFC 1929 user/password auth and returns the CONNECT target.
func (p *directProxy) handshake(c net.Conn) (string, int, error) {
	buf := make([]byte, 262)
	if _, err := io.ReadFull(c, buf[:2]); err != nil || buf[0] != 5 {
		return "", 0, errors.New("not socks5")
	}
	methods := buf[2 : 2+int(buf[1])]
	if _, err := io.ReadFull(c, methods); err != nil {
		return "", 0, err
	}
	hasAuth := false
	for _, m := range methods {
		hasAuth = hasAuth || m == 2
	}
	if !hasAuth {
		_, _ = c.Write([]byte{5, 0xff})
		return "", 0, errors.New("no auth method")
	}
	if _, err := c.Write([]byte{5, 2}); err != nil {
		return "", 0, err
	}
	// user/password sub-negotiation
	if _, err := io.ReadFull(c, buf[:2]); err != nil || buf[0] != 1 {
		return "", 0, errors.New("bad auth")
	}
	user := make([]byte, buf[1])
	if _, err := io.ReadFull(c, user); err != nil {
		return "", 0, err
	}
	if _, err := io.ReadFull(c, buf[:1]); err != nil {
		return "", 0, err
	}
	pass := make([]byte, buf[0])
	if _, err := io.ReadFull(c, pass); err != nil {
		return "", 0, err
	}
	if string(user) != p.user || string(pass) != p.pass {
		_, _ = c.Write([]byte{1, 1})
		return "", 0, errors.New("wrong credentials")
	}
	if _, err := c.Write([]byte{1, 0}); err != nil {
		return "", 0, err
	}
	// request
	if _, err := io.ReadFull(c, buf[:4]); err != nil || buf[0] != 5 {
		return "", 0, errors.New("bad request")
	}
	if buf[1] != 1 { // CONNECT only
		reply(c, 7)
		return "", 0, errors.New("command not supported")
	}
	var host string
	switch buf[3] {
	case 1:
		if _, err := io.ReadFull(c, buf[:4]); err != nil {
			return "", 0, err
		}
		host = net.IP(append([]byte(nil), buf[:4]...)).String()
	case 4:
		if _, err := io.ReadFull(c, buf[:16]); err != nil {
			return "", 0, err
		}
		host = net.IP(append([]byte(nil), buf[:16]...)).String()
	case 3:
		if _, err := io.ReadFull(c, buf[:1]); err != nil {
			return "", 0, err
		}
		name := make([]byte, buf[0])
		if _, err := io.ReadFull(c, name); err != nil {
			return "", 0, err
		}
		host = string(name)
	default:
		reply(c, 8)
		return "", 0, errors.New("address type")
	}
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return "", 0, err
	}
	return host, int(binary.BigEndian.Uint16(buf[:2])), nil
}

func reply(c net.Conn, code byte) error {
	_, err := c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
