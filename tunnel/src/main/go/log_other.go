//go:build !android

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"log"
	"os"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
)

// Host builds exist only for tests of the JNI glue.
func newLogger(tag string, verbose bool) *device.Logger {
	l := log.New(os.Stderr, tag+": ", log.LstdFlags)
	lg := &device.Logger{Verbosef: device.DiscardLogf, Errorf: l.Printf}
	if verbose {
		lg.Verbosef = l.Printf
	}
	return lg
}
