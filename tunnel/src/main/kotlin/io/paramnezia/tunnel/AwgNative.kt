package io.paramnezia.tunnel

import io.paramnezia.core.fleet.Ed25519

/** JNI surface of libparamnezia.so (src/main/go). */
object AwgNative {
    init {
        System.loadLibrary("paramnezia")
    }

    /** Returns a tunnel handle (>= 0) or a negative error. Takes ownership of [tunFd]. */
    @JvmStatic external fun turnOn(name: String, tunFd: Int, uapi: String): Int
    @JvmStatic external fun turnOff(handle: Int)
    @JvmStatic external fun socketV4(handle: Int): Int
    @JvmStatic external fun socketV6(handle: Int): Int
    @JvmStatic external fun getConfig(handle: Int): String?
    @JvmStatic external fun setConfig(handle: Int, uapi: String): Int
    @JvmStatic external fun version(): String?
    @JvmStatic external fun setVerbose(on: Boolean)
    @JvmStatic external fun ed25519Generate(): ByteArray?
    @JvmStatic external fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray?
    @JvmStatic external fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** Ed25519 from Go's standard library; works on every Android version. */
object NativeEd25519 : Ed25519 {
    override fun generate(): Pair<ByteArray, ByteArray> {
        val b = AwgNative.ed25519Generate() ?: error("Ed25519 key generation failed")
        return b.copyOfRange(0, 32) to b.copyOfRange(32, 64)
    }

    override fun sign(seed: ByteArray, message: ByteArray): ByteArray =
        AwgNative.ed25519Sign(seed, message) ?: error("Ed25519 sign failed")

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        AwgNative.ed25519Verify(publicKey, message, signature)
}
