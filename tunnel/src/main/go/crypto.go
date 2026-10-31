/* SPDX-License-Identifier: Apache-2.0 */

package main

import "C"

import (
	"crypto/ed25519"
	"crypto/rand"
	"unsafe"
)

// Ed25519 for Android releases without it in the platform (before 13). Keys are raw 32-byte seeds
// and public keys, as used by the fleet manifest.

//export pnEd25519Generate
func pnEd25519Generate(seedOut, pubOut unsafe.Pointer) int32 {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return -1
	}
	copy(unsafe.Slice((*byte)(seedOut), 32), priv.Seed())
	copy(unsafe.Slice((*byte)(pubOut), 32), pub)
	return 0
}

//export pnEd25519Sign
func pnEd25519Sign(seed unsafe.Pointer, msg unsafe.Pointer, msgLen int32, sigOut unsafe.Pointer) {
	key := ed25519.NewKeyFromSeed(C.GoBytes(seed, 32))
	sig := ed25519.Sign(key, C.GoBytes(msg, C.int(msgLen)))
	copy(unsafe.Slice((*byte)(sigOut), 64), sig)
}

//export pnEd25519Verify
func pnEd25519Verify(pub unsafe.Pointer, msg unsafe.Pointer, msgLen int32, sig unsafe.Pointer) bool {
	return ed25519.Verify(C.GoBytes(pub, 32), C.GoBytes(msg, C.int(msgLen)), C.GoBytes(sig, 64))
}
