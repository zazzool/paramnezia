package net.paramnezia.app.data

import net.paramnezia.core.fleet.FleetTrust
import net.paramnezia.core.profile.SshCredentials
import kotlinx.serialization.Serializable

/** Everything the app keeps. Stored encrypted with a hardware-backed key (see [Vault]). */
@Serializable
data class AppState(
    val profiles: List<StoredProfile> = emptyList(),
    val activeProfile: String? = null,
    val fleet: StoredFleet? = null,
    val settings: Settings = Settings(),
    /** SSH host key fingerprints, trust on first use: "host:port" → "SHA256:…". */
    val hostKeys: Map<String, String> = emptyMap(),
    /** Newest verified app release (SignedRelease wire form), shared with peers. */
    val release: String? = null,
    /** The user wants the tunnel up (restored after an update). */
    val wantConnected: Boolean = false,
    val uiMode: UiMode = UiMode.AUTO,
    /** Admin: one-time links handed out, newest first. */
    val links: List<StoredLink> = emptyList(),
) {
    /** Follower devices of a fleet get the one-button screen; the admin and standalone users get everything. */
    val simpleUi: Boolean get() = when (uiMode) {
        UiMode.SIMPLE -> true
        UiMode.FULL -> false
        UiMode.AUTO -> fleet != null && fleet.adminSeed == null
    }
}

enum class UiMode { AUTO, SIMPLE, FULL }

/** A standalone server: imported from a key or deployed from the app. */
@Serializable
data class StoredProfile(
    val id: String,
    val name: String,
    /** Client config in wg-quick form (with AmneziaWG fields). */
    val conf: String,
    val protocol: String = "AWG",
    val ssh: SshCredentials? = null,
)

/** Membership in a fleet: this device's key plus the newest manifest it has seen. */
@Serializable
data class StoredFleet(
    val trust: FleetTrust,
    /** WireGuard private key of this device, base64. */
    val deviceKey: String,
    val deviceName: String,
    /** SignedManifest wire form. */
    val manifest: String,
    /** Present on the admin device only: Ed25519 signing seed, base64. */
    val adminSeed: String? = null,
    /** SSH access per server id, admin only (to add devices and publish manifests). */
    val serverAccess: Map<String, SshCredentials> = emptyMap(),
    /** Optional GitHub gist mirror, admin only. */
    val gistId: String? = null,
    val gistToken: String? = null,
    /** Last time each server answered, by id (for health reports). */
    val lastSync: Long = 0,
    /**
     * Admin: public keys of removed devices. Every self-check removes them from every server again,
     * so a server that was unreachable at removal time cannot keep a revoked device connected.
     */
    val revoked: Set<String> = emptySet(),
    /** Admin: when the servers were last checked and repaired (see FleetManager.heal). */
    val lastHeal: Long = 0,
)

@Serializable
data class Settings(
    /** Russian IPs, domains and apps bypass the tunnel. */
    val bypassRussia: Boolean = true,
    val bypassLan: Boolean = true,
    /** Ad and tracker blocking in the tunnel's DNS. */
    val blockTrackers: Boolean = true,
    val forceTunnel: Set<String> = emptySet(),
    val forceDirect: Set<String> = emptySet(),
    val battery: BatteryMode = BatteryMode.BALANCED,
    val autoUpdate: Boolean = true,
    val relays: List<String> = emptyList(),
    /** Extra Russian resolvers for the split DNS; empty uses the defaults. */
    val directDns: List<String> = emptyList(),
)

/** Keepalive while the screen is on / off. Off = no packets at all while idle. */
enum class BatteryMode(val screenOn: String, val screenOff: String) {
    MAX_SAVING("0", "0"),
    BALANCED("25-35", "55"),
    RELIABLE("25", "25"),
}

@Serializable
data class StoredLink(
    val token: String,
    val url: String,
    val name: String,
    /** Server that serves the link: a fleet server id or a profile id. */
    val serverRef: String,
    val createdAt: Long,
    val expiresAt: Long,
    val used: Boolean = false,
    /** The key the link hands out, so taking the link back also takes the access back. */
    val peerPublicKey: String? = null,
    val fleetDevice: Boolean = false,
)
