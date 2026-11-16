package net.paramnezia.app.tunnel

import net.paramnezia.app.data.Repo
import net.paramnezia.app.platform.Log
import net.paramnezia.core.config.Camouflage
import net.paramnezia.core.config.CamouflageLadder
import net.paramnezia.core.config.PathMtu
import net.paramnezia.core.config.TunnelConfig

/**
 * The app's side of [Camouflage]: which variant each server is dialled with on the current network,
 * what to try next when a server stops answering, and what to remember when it answers. Shared by
 * the Android and Windows apps; the memory lives in the app state (`AppState.camouflage`).
 */
class CamouflagePlanner(private val repo: Repo) {
    private val ladder = CamouflageLadder(repo.current.camouflage) { m -> repo.update { it.copy(camouflage = m) } }

    /** Where a server can be reached besides its config's endpoint. */
    data class Reach(val altPorts: List<Int> = emptyList(), val altHosts: List<String> = emptyList())

    /** A config as it goes to the tunnel, the variant it was dressed as, and its padding plan. */
    data class Dressed(val config: TunnelConfig, val variant: String, val padding: Camouflage.Padding?)

    @Volatile private var network: String = ""
    @Volatile private var ipv6: Boolean = true

    /**
     * The network the device is on now (an opaque key, see the platforms' network identity) and
     * whether it has IPv6. A new network starts every server from what worked there before.
     */
    fun onNetwork(key: String, hasIpv6: Boolean) {
        // No network for a moment (the failure that made us look) is not a new network.
        if (key == "offline" || key == "unknown") return
        ipv6 = hasIpv6
        if (key != network) {
            if (network.isNotEmpty()) Log.i(TAG, "network changed")
            network = key
            ladder.networkChanged()
        }
    }

    private fun steps(config: TunnelConfig, reach: Reach) = Camouflage.ladderOf(config, reach.altPorts, reach.altHosts, ipv6)

    /** [config] for [serverId], dressed as the variant to use now. */
    fun dress(serverId: String, config: TunnelConfig, reach: Reach): Dressed {
        val v = ladder.pick(network, serverId, steps(config, reach)) ?: return Dressed(config, "", null)
        return Dressed(Camouflage.apply(config, v), v.id, Camouflage.padding(config))
    }

    /**
     * [failed] (a variant id of [serverId]) did not answer: the next variant to try, or null when it
     * is time for another server ([mayStay] false and this round is over; see [CamouflageLadder.next]).
     */
    fun next(serverId: String, config: TunnelConfig, reach: Reach, failed: String, mayStay: Boolean): Dressed? {
        val list = steps(config, reach)
        val v = ladder.next(serverId, failed, list, mayStay) ?: return null
        Log.i(TAG, "trying ${v.mimic.id} on port ${v.port}${if (':' in v.host) " (IPv6)" else ""}")
        return Dressed(Camouflage.apply(config, v), v.id, Camouflage.padding(config))
    }

    /** Variants of [serverId] after [first], in order: what the Windows service walks through by itself. */
    fun rest(serverId: String, config: TunnelConfig, reach: Reach, first: String, max: Int = Int.MAX_VALUE): List<Dressed> {
        val list = steps(config, reach)
        val at = list.indexOfFirst { it.id == first }.coerceAtLeast(0)
        return (1 until list.size).asSequence().map { list[(at + it) % list.size] }.take(max)
            .map { v -> Dressed(Camouflage.apply(config, v), v.id, Camouflage.padding(config)) }.toList()
    }

    /**
     * [config] (as the server gave it) with the MTU to build with on this network, and the probe to
     * run once it works (see PathMtu).
     */
    fun sized(serverId: String, raw: TunnelConfig, dressed: TunnelConfig): Pair<TunnelConfig, String?> {
        val mtu = PathMtu.choose(raw, mtuFor(serverId))
        return dressed.copy(iface = dressed.iface.copy(mtu = mtu)) to PathMtu.probeSpec(raw)
    }

    private fun mtuFor(serverId: String): Int? = if (network.isEmpty()) null else repo.current.pathMtu["$network|$serverId"]

    /**
     * A probe on [serverId] found [best] bytes (0: none came back). Remembers the MTU for this
     * network and says whether a tunnel built with [current] should be rebuilt for it: always to go
     * down (larger packets would be lost), to go up only for a real gain.
     */
    fun measured(serverId: String, raw: TunnelConfig, current: Int, best: Int): Boolean {
        val mtu = PathMtu.fromProbe(raw, best)
        if (network.isNotEmpty()) {
            val key = "$network|$serverId"
            if (repo.current.pathMtu[key] != mtu) repo.update { s ->
                val m = LinkedHashMap(s.pathMtu); m.remove(key); m[key] = mtu
                while (m.size > CamouflageLadder.MAX_ENTRIES) m.remove(m.keys.first())
                s.copy(pathMtu = m)
            }
        }
        return mtu < current || mtu >= current + MTU_STEP
    }

    /** [variant] of [serverId] carried traffic on the current network. */
    fun worked(serverId: String, variant: String) {
        if (variant.isEmpty() || network.isEmpty()) return
        ladder.worked(network, serverId, variant)
    }

    private companion object {
        const val TAG = "PnCamouflage"
        /** Rebuilding to gain less than this is not worth a new handshake. */
        const val MTU_STEP = 40
    }
}
