package net.paramnezia.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.paramnezia.app.fleet.FleetManager
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ServerManager
import net.paramnezia.deploy.ServerStatus

/**
 * Everything the server details screen shows about one server, from what the app already holds
 * (the config this device dials, spare ports, roles) plus, on request, what the server itself says
 * over SSH ([status]). Shared by the Android and Windows apps.
 */
class ServerInsights(private val repo: Repo, private val fleet: FleetManager) {

    /** How this device's packets are protected beyond X25519. */
    enum class Psk(val label: String) { OWN("свой у этого устройства"), SHARED("общий для сервера"), NONE("нет") }

    data class View(
        val id: String,
        val name: String,
        /** "основной", "резервный" in a family; null for a standalone server. */
        val role: String?,
        /** The config this device connects with (before the per-network dressing). */
        val config: TunnelConfig,
        val altPorts: List<Int>,
        val altHosts: List<String>,
        val ssh: SshCredentials?,
        val protocol: Protocol,
        val psk: Psk,
    ) {
        val endpoint: String? get() = config.peers.firstNotNullOfOrNull { it.endpoint }
    }

    /** Every server this device can connect to: the family's (master first), then standalone ones. */
    fun views(): List<View> {
        val out = ArrayList<View>()
        runCatching {
            val member = fleet.member()
            val m = member?.manifest
            val me = m?.deviceFor(member.deviceKey.publicKey())
            member?.tunnels()?.forEachIndexed { i, (s, config) ->
                val psk = when {
                    config.peers.firstOrNull()?.presharedKey == null -> Psk.NONE
                    me?.ownPsk == true && member.devicePsk != null -> Psk.OWN
                    else -> Psk.SHARED
                }
                out += View(s.id, s.name, if (i == 0) "основной" else "резервный", config, s.altPorts, s.altHosts,
                    fleet.accessTo(s.id), Protocol.AWG, psk)
            }
        }
        for (p in repo.current.profiles) {
            val config = runCatching { WgQuick.parse(p.conf) }.getOrNull() ?: continue
            val protocol = runCatching { Protocol.valueOf(p.protocol) }.getOrDefault(Protocol.AWG)
            // Standalone accounts made by 2.16+ have their own key; older ones may share the server's.
            val psk = if (config.peers.firstOrNull()?.presharedKey == null) Psk.NONE else Psk.OWN.takeIf { p.portsChecked > 0 } ?: Psk.SHARED
            out += View(p.id, p.name, null, config, p.altPorts, p.altHosts, p.ssh, protocol, psk)
        }
        return out
    }

    fun view(id: String): View? = views().firstOrNull { it.id == id }

    /** The server's own report (engine, peers, ports, tuning, host), over SSH; needs admin access. */
    suspend fun status(id: String): ServerStatus = withContext(Dispatchers.IO) {
        val v = view(id) ?: error("Сервер не найден")
        val ssh = v.ssh ?: error("Нет SSH-доступа к этому серверу")
        ServerManager(repo.hostKeys).status(ssh, v.protocol)
    }

    /** The server's public key in the short form people compare by eye. */
    fun shortKey(config: TunnelConfig): String = config.peers.firstOrNull()?.publicKey?.toBase64()?.let { it.take(8) + "…" + it.takeLast(6) } ?: "—"

    /** The full public key, for copying. */
    fun serverKey(config: TunnelConfig): String? = config.peers.firstOrNull()?.publicKey?.let(WgKey::toBase64)
}
