//go:build android

/* SPDX-License-Identifier: Apache-2.0 */

package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
import "C"

import (
	"fmt"
	"unsafe"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"golang.org/x/sys/unix"
)

func cstring(s string) *C.char {
	b, err := unix.BytePtrFromString(s)
	if err != nil {
		z := [1]C.char{}
		return &z[0]
	}
	return (*C.char)(unsafe.Pointer(b))
}

func newLogger(tag string, verbose bool) *device.Logger {
	t := cstring(tag)
	l := &device.Logger{
		Verbosef: device.DiscardLogf,
		Errorf: func(format string, args ...any) {
			C.__android_log_write(C.ANDROID_LOG_ERROR, t, cstring(fmt.Sprintf(format, args...)))
		},
	}
	if verbose {
		l.Verbosef = func(format string, args ...any) {
			C.__android_log_write(C.ANDROID_LOG_DEBUG, t, cstring(fmt.Sprintf(format, args...)))
		}
	}
	return l
}
