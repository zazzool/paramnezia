package io.paramnezia.core.fleet

import io.paramnezia.core.amnezia.AmneziaFormat
import io.paramnezia.core.amnezia.AmneziaKey
import io.paramnezia.core.config.ConfigException
import io.paramnezia.core.config.TunnelConfig
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.net.IpPrefix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.util.UUID

/** Admin side: owns the signing seed and produces new manifest versions. */
class FleetAdmin(
    val trust: FleetTrust,
    /** Ed25519 seed; stays on the admin device. */
    val signingSeed: ByteArray,
    private val ed: Ed25519,
) {
    fun seal(m: FleetManifest): SignedManifest = FleetCrypto.seal(m, trust.fleetKey, signingSeed, ed)

    /** Next version with [change] applied; versions are wall-clock based and always increase. */
    fun next(current: FleetManifest, change: (FleetManifest) -> FleetManifest): FleetManifest {
        val now = System.currentTimeMillis()
        return change(current).copy(version = maxOf(current.version + 1, now), issuedAt = now)
    }

    /** A new trusted device: its key pair is made here so joining needs no round trip. */
    fun newDevice(current: FleetManifest, name: String, subnet: IpPrefix = DEFAULT_SUBNET): Pair<FleetDevice, WgKey> {
        val key = WgKey.generatePrivate()
        val taken = current.devices.mapTo(HashSet()) { IpPrefix.parse(it.address).network }
        // .1 is the server; hand out .2, .3, … in order, like AmneziaVPN does.
        var candidate = subnet.network + BigInteger.valueOf(2)
        while (candidate in taken) candidate += BigInteger.ONE
        require(candidate < subnet.last) { "Fleet subnet is full" }
        val addr = IpPrefix.of(false, candidate, 32)
        return FleetDevice(name, key.publicKey().toBase64(), addr.toString()) to key
    }

    /**
     * The join link for a device: an ordinary AmneziaVPN `vpn://` key for the master server (so the
     * original app can import it too) that additionally carries the fleet trust and manifest.
     */
    fun invite(manifest: FleetManifest, device: FleetDevice, key: WgKey): String {
        val master = manifest.servers.firstOrNull() ?: throw ConfigException("Fleet has no servers")
        val base = AmneziaFormat.export(master.name, master.tunnelFor(device, key))
        val extra = Invite(trust, device.name, key.toBase64(), FleetCrypto.encode(seal(manifest)))
        return AmneziaKey.encode(JsonObject(base + (INVITE_KEY to json.encodeToJsonElement(Invite.serializer(), extra))))
    }

    companion object {
        val DEFAULT_SUBNET: IpPrefix = IpPrefix.parse("10.8.1.0/24")

        fun create(name: String, ed: Ed25519): Pair<FleetAdmin, FleetManifest> {
            val (seed, pub) = ed.generate()
            val trust = FleetTrust(UUID.randomUUID().toString(), java.util.Base64.getEncoder().encodeToString(pub), FleetCrypto.newFleetKey())
            val m = FleetManifest(trust.fleetId, 1, System.currentTimeMillis(), emptyList(), emptyList(), emptyList())
            return FleetAdmin(trust, seed, ed) to m.also { require(name.isNotBlank()) }
        }
    }
}

/** Extra payload inside a `vpn://` invite; AmneziaVPN ignores unknown keys. */
@Serializable
data class Invite(
    val trust: FleetTrust,
    val deviceName: String,
    val privateKey: String,
    val manifest: String,
) {
    companion object {
        /** The fleet part of a `vpn://` invite, or null for an ordinary key. */
        fun read(text: String): Invite? {
            val obj = runCatching { AmneziaKey.decode(text.trim()) }.getOrNull() ?: return null
            return obj[INVITE_KEY]?.let { runCatching { json.decodeFromJsonElement(serializer(), it.jsonObject) }.getOrNull() }
        }

        /**
         * The same key without the fleet part: what goes to the original AmneziaVPN (an iPhone, say),
         * which needs only the connection, not the fleet's keys.
         */
        fun plainKey(text: String): String {
            val obj = AmneziaKey.decode(text.trim())
            return if (INVITE_KEY in obj) AmneziaKey.encode(JsonObject(obj - INVITE_KEY)) else text.trim()
        }
    }
}

const val INVITE_KEY = "paramnezia_fleet"
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Device side: keeps the newest valid manifest and turns it into tunnels. */
class FleetMember(
    val trust: FleetTrust,
    val deviceKey: WgKey,
    private val ed: Ed25519,
    manifest: FleetManifest? = null,
) {
    var manifest: FleetManifest? = manifest
        private set

    /**
     * Offers a manifest fetched from anywhere. Returns true if it was accepted as a newer version.
     * Forged, foreign, older or replayed manifests are ignored.
     */
    fun offer(text: String): Boolean {
        val signed = FleetCrypto.decode(text.trim()) ?: return false
        if (signed.version <= (manifest?.version ?: 0)) return false
        // A manifest that no longer lists this device is accepted too: the device was revoked, and
        // remembering the version stops older manifests from being replayed to it.
        manifest = FleetCrypto.open(signed, trust, ed) ?: return false
        return true
    }

    /** Tunnels for this device, master first. Empty when the device was removed from the fleet. */
    fun tunnels(): List<Pair<FleetServer, TunnelConfig>> {
        val m = manifest ?: return emptyList()
        val me = m.deviceFor(deviceKey.publicKey()) ?: return emptyList()
        return m.servers.map { it to it.tunnelFor(me, deviceKey) }
    }

    companion object {
        /** Reads an invite produced by [FleetAdmin.invite]. Returns null for an ordinary vpn:// key. */
        fun fromInvite(text: String, ed: Ed25519): FleetMember? {
            val inv = Invite.read(text) ?: return null
            val member = FleetMember(inv.trust, WgKey.fromBase64(inv.privateKey), ed)
            if (!member.offer(inv.manifest)) return null
            return member
        }
    }
}
