package dnsfilter

import (
	"encoding/binary"
	"fmt"
	"testing"
	"time"
)

// answerA builds a NOERROR answer to query(id, name, A) with one A record and the given TTL.
func answerA(name string, ttl uint32) []byte {
	q := query(1, name, 1)
	r := append([]byte(nil), q...)
	r[2] |= 0x80
	r[3] = 0x80
	binary.BigEndian.PutUint16(r[6:], 1)
	rr := []byte{0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4}
	binary.BigEndian.PutUint32(rr[6:], ttl)
	return append(r, rr...)
}

func ttlOf(m []byte) uint32 {
	i := skipName(m, 12) + 4
	i = skipName(m, i)
	return binary.BigEndian.Uint32(m[i+4:])
}

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func TestCacheFreshStaleAndExpired(t *testing.T) {
	clk := &clock{time.Unix(1_700_000_000, 0)}
	c := newDNSCache()
	c.now = clk.now
	c.put("k", answerA("example.com", 100))

	r, fresh, prefetch := c.lookup("k", 7)
	if r == nil || !fresh || prefetch || binary.BigEndian.Uint16(r) != 7 || ttlOf(r) != 100 {
		t.Fatalf("fresh hit: fresh=%v prefetch=%v ttl=%d", fresh, prefetch, ttlOf(r))
	}
	// Last tenth of the life: still fresh, refreshed in the background, once.
	clk.t = clk.t.Add(95 * time.Second)
	if r, fresh, prefetch = c.lookup("k", 8); !fresh || !prefetch || ttlOf(r) != 5 {
		t.Fatalf("prefetch window: fresh=%v prefetch=%v ttl=%d", fresh, prefetch, ttlOf(r))
	}
	if _, _, prefetch = c.lookup("k", 9); prefetch {
		t.Fatal("prefetch asked twice")
	}
	// Past the TTL: offered as stale (with a short TTL) for the caller to use if upstream fails.
	clk.t = clk.t.Add(time.Hour)
	if r, fresh, _ = c.lookup("k", 10); r == nil || fresh || ttlOf(r) != staleTTL {
		t.Fatalf("stale: r=%v fresh=%v", r != nil, fresh)
	}
	// Past the stale window: gone.
	clk.t = clk.t.Add(staleFor)
	if r, _, _ = c.lookup("k", 11); r != nil {
		t.Fatal("served an answer older than the stale window")
	}
}

func TestCacheEvictsOneNotAll(t *testing.T) {
	clk := &clock{time.Unix(1_700_000_000, 0)}
	c := newDNSCache()
	c.now = clk.now
	for i := 0; i < cacheSize; i++ {
		c.put(fmt.Sprint(i), answerA("example.com", 300))
		clk.t = clk.t.Add(time.Millisecond)
	}
	// A name in use survives the pressure of many new ones (sampled LRU keeps recent names).
	for i := 0; i < cacheSize/2; i++ {
		c.lookup("hot", 1)
		if i == 0 {
			c.put("hot", answerA("example.com", 300))
		}
		c.put(fmt.Sprint("new", i), answerA("example.com", 300))
		clk.t = clk.t.Add(time.Millisecond)
	}
	if len(c.m) != cacheSize {
		t.Fatalf("size %d, want %d (no emptying at the limit)", len(c.m), cacheSize)
	}
	if r, _, _ := c.lookup("hot", 1); r == nil {
		t.Fatal("the name in use was evicted")
	}
}

func TestErrorsAreNotCached(t *testing.T) {
	c := newDNSCache()
	bad := answerA("example.com", 100)
	bad[3] = 0x83 // NXDOMAIN
	c.put("k", bad)
	if r, _, _ := c.lookup("k", 1); r != nil {
		t.Fatal("an error answer was cached")
	}
}
