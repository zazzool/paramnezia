package net.paramnezia.core.profile

import net.paramnezia.core.config.TunnelConfig
import kotlinx.serialization.Serializable

/** SSH access to a self-hosted server: what AmneziaVPN calls a "full access" config. */
@Serializable
data class SshCredentials(
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    /** Password, or empty when [privateKey] is used. */
    val password: String = "",
    /** OpenSSH/PEM private key text. */
    val privateKey: String = "",
    val passphrase: String = "",
)

enum class Protocol(val amneziaContainer: String, val amneziaKey: String) {
    /** AmneziaWG 2.0+ container that current AmneziaVPN installs. */
    AWG("amnezia-awg2", "awg"),
    /** AmneziaWG 1.x container from older AmneziaVPN releases. */
    AWG_LEGACY("amnezia-awg", "awg"),
    WIREGUARD("amnezia-wireguard", "wireguard"),
    XRAY("amnezia-xray", "xray");

    companion object {
        fun fromContainer(name: String): Protocol? = entries.firstOrNull { it.amneziaContainer == name }
    }
}

/** A server the app can connect to and, when [ssh] is set, manage. */
data class ServerProfile(
    val name: String,
    val host: String,
    val protocol: Protocol,
    /** Ready-to-use client tunnel; null when the protocol is not supported in this build (XRay). */
    val tunnel: TunnelConfig?,
    val ssh: SshCredentials? = null,
    val dns: List<String> = emptyList(),
)
