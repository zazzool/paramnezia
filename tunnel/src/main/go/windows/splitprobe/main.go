/* SPDX-License-Identifier: Apache-2.0 */

// splitprobe is a CI helper for the "programs past the VPN" check (.github/scripts/
// windows-service-check.ps1): it opens a TCP connection and asks one DNS question over UDP to the
// address given, and prints which of the two got through. The check runs it under two file names,
// one of them on the list, with that address routed into a tunnel that goes nowhere.
package main

import (
	"encoding/json"
	"net"
	"os"
	"time"
)

func main() {
	target := "1.1.1.1"
	if len(os.Args) > 1 {
		target = os.Args[1]
	}
	out := map[string]bool{"tcp": false, "udp": false}
	if c, err := net.DialTimeout("tcp", net.JoinHostPort(target, "443"), 5*time.Second); err == nil {
		out["tcp"] = true
		c.Close()
	}
	if c, err := net.Dial("udp", net.JoinHostPort(target, "53")); err == nil {
		// A query for example.com, type A.
		q := []byte{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}
		buf := make([]byte, 512)
		for i := 0; i < 3 && !out["udp"]; i++ {
			c.Write(q)
			c.SetReadDeadline(time.Now().Add(2 * time.Second))
			if n, err := c.Read(buf); err == nil && n > 12 && buf[0] == 0x12 && buf[1] == 0x34 {
				out["udp"] = true
			}
		}
		c.Close()
	}
	json.NewEncoder(os.Stdout).Encode(out)
}
