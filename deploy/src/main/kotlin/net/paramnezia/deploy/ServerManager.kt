package net.paramnezia.deploy

import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.net.IpPrefix
import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.profile.SshCredentials
import java.security.SecureRandom

/**
 * Installs and manages AmneziaWG on a VPS over SSH, in AmneziaVPN's server layout. All calls block;
 * run them off the main thread.
 */
class ServerManager(private val hostKeys: HostKeyStore) {

    data class InstallOptions(
        val port: Int = randomPort(),
        val subnet: String = "10.8.1.0",
        val cidr: Int = 24,
        val profile: ObfuscationProfile = ObfuscationProfile.AWG_3,
        /** Replace an existing amnezia-awg2 container (drops its clients). */
        val reinstall: Boolean = false,
        /** More host ports leading to the same listener ([altPortsFor]); a busy one is skipped. */
        val altPorts: List<Int> = altPortsFor(port),
    )

    /** The ports a server answers on and its IPv6 address, if clients can reach it there. */
    data class Ports(val main: Int, val alt: List<Int>, val ipv6: String?)

    enum class Step { CHECK, DOCKER, IMAGE, RUN, CONFIGURE, START, FIREWALL, DONE }

    data class Inspection(val dockerInstalled: Boolean, val containers: Map<String, String>, val system: String) {
        /** The AmneziaWG container to use, preferring the current AmneziaVPN layout. */
        val awgContainer: Protocol? get() = when {
            "amnezia-awg2" in containers -> Protocol.AWG
            "amnezia-awg" in containers -> Protocol.AWG_LEGACY
            "amnezia-wireguard" in containers -> Protocol.WIREGUARD
            else -> null
        }
    }

    fun inspect(creds: SshCredentials): Inspection = SshConnection.open(creds, hostKeys).use { ssh ->
        val r = ssh.run(script("inspect.sh"))
        val containers = r.stdout.lineSequence().filter { it.startsWith("::container::") }
            .map { it.removePrefix("::container::").split(' ') }
            .associate { it[0] to it.getOrElse(1) { "" } }
        Inspection(r.results["docker"] == "present", containers, r.results["uname"].orEmpty())
    }

    /** Client addresses already in use on the server (without the prefix length). */
    fun usedAddresses(creds: SshCredentials, protocol: Protocol = Protocol.AWG): Set<String> = SshConnection.open(creds, hostKeys).use { ssh ->
        val r = ssh.run(
            "if [ \"\$(id -u)\" -eq 0 ]; then S=''; else S='sudo -n'; fi; " +
                "\$S docker exec ${protocol.amneziaContainer} sh -c 'cat /opt/amnezia/awg/awg0.conf /opt/amnezia/awg/wg0.conf 2>/dev/null' | " +
                "awk '\$1==\"AllowedIPs\"{for(i=3;i<=NF;i++){gsub(\",\",\"\",\$i); print \$i}}'",
        )
        r.stdout.lines().map { it.trim().substringBefore('/') }.filter { it.isNotEmpty() }.toSet()
    }

    /** Installs the server. Returns the obfuscation parameters every client must share. */
    fun install(creds: SshCredentials, options: InstallOptions = InstallOptions(), onStep: (Step) -> Unit = {}): Map<AwgParam, String> {
        val params = options.profile.generate()
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(
                script("install-awg.sh"),
                env = mapOf(
                    "AWG_PORT" to options.port.toString(),
                    "AWG_SUBNET" to firstHost(options.subnet),
                    "AWG_CIDR" to options.cidr.toString(),
                    "AWG_PARAMS" to params.entries.joinToString("\n") { "${it.key.conf} = ${it.value}" },
                    "PN_REINSTALL" to if (options.reinstall) "1" else "0",
                    "ALT_PORTS" to options.altPorts.joinToString(" "),
                ),
            ) { line ->
                if (line.startsWith("::step::")) {
                    runCatching { Step.valueOf(line.removePrefix("::step::").uppercase()) }.getOrNull()?.let(onStep)
                }
            }
            r.error?.let { throw SshException(it, "Install failed: $it\n${r.stderr.takeLast(2000)}") }
            if (r.exitCode != 0) throw SshException("install_failed", "Install failed (${r.exitCode}): ${r.stderr.takeLast(2000)}")
            // Throughput tuning is a bonus: a server without it still works.
            runCatching { ssh.run(script("tune.sh"), env = mapOf("CONTAINER" to Protocol.AWG.amneziaContainer), timeoutMs = 60_000) }
        }
        return params
    }

    /**
     * Registers [clientPrivateKey]'s public key as a new peer and returns a ready client config.
     * Idempotent: asking again for the same key returns the same address.
     */
    fun addClient(
        creds: SshCredentials,
        clientPrivateKey: WgKey,
        name: String,
        protocol: Protocol = Protocol.AWG,
        dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),
        mtu: Int = 1280,
        keepalive: String? = null,
        /** Fixed tunnel address (fleet devices); null lets the server pick the next free one. */
        clientIp: String? = null,
        /** The client's own preshared key; null takes the server's shared one (AmneziaVPN's way). */
        psk: WgKey? = null,
    ): TunnelConfig {
        val r = registerPeer(creds, clientPrivateKey.publicKey(), name, protocol, clientIp, psk)
        return clientConfig(r.serverConf, r.results, clientPrivateKey, creds.host, dns, mtu, keepalive)
    }

    /** What the server answered when a peer was added. */
    class PeerRegistration(val serverConf: String, val results: Map<String, String>) {
        val clientIp: String get() = results["client_ip"].orEmpty()
    }

    /**
     * Registers a peer by its public key only (fleet devices whose private keys never leave the
     * device). Idempotent, like [addClient].
     */
    fun registerPeer(
        creds: SshCredentials,
        publicKey: WgKey,
        name: String,
        protocol: Protocol = Protocol.AWG,
        clientIp: String? = null,
        /** The peer's own preshared key; also replaces another key an existing peer has. */
        psk: WgKey? = null,
    ): PeerRegistration = SshConnection.open(creds, hostKeys).use { ssh ->
        val r = ssh.run(script("add-peer.sh"), env = peerEnv(protocol, PeerSpec(publicKey, name, clientIp, psk)))
        r.error?.let { throw SshException(it, "Could not add client: $it") }
        if (r.exitCode != 0) throw SshException("add_peer_failed", "Could not add client: ${r.stderr.takeLast(2000)}")
        PeerRegistration(r.stdout.substringAfter("::conf-begin::\n", "").substringBefore("::conf-end::"), r.results)
    }

    /** A peer to make sure of: public key, name for clientsTable, fixed address (or null), own preshared key (or null). */
    data class PeerSpec(val publicKey: WgKey, val name: String, val clientIp: String?, val psk: WgKey? = null)

    private fun peerEnv(protocol: Protocol, p: PeerSpec): Map<String, String> =
        mapOf("CONTAINER" to protocol.amneziaContainer, "CLIENT_PUB" to p.publicKey.toBase64(), "CLIENT_NAME" to p.name) +
            (p.clientIp?.let { mapOf("CLIENT_IP" to it) } ?: emptyMap()) +
            (p.psk?.let { mapOf("CLIENT_PSK" to it.toBase64()) } ?: emptyMap())

    /**
     * Makes sure every peer in [peers] is on the server, over one connection. Already registered
     * peers are left as they are (the script is idempotent). Returns the peers that could not be
     * registered, by public key, with the reason; the caller retries them later.
     */
    fun ensurePeers(creds: SshCredentials, peers: List<PeerSpec>, protocol: Protocol = Protocol.AWG): Map<String, String> {
        if (peers.isEmpty()) return emptyMap()
        val failed = LinkedHashMap<String, String>()
        SshConnection.open(creds, hostKeys).use { ssh ->
            for (p in peers) {
                val r = runCatching {
                    ssh.run(script("add-peer.sh"), env = peerEnv(protocol, p), timeoutMs = 60_000)
                }.getOrElse { failed[p.publicKey.toBase64()] = it.message ?: "error"; continue }
                if (r.error != null || r.exitCode != 0) failed[p.publicKey.toBase64()] = r.error ?: r.stderr.takeLast(300)
            }
        }
        return failed
    }

    /** Removes several peers over one connection (absent ones are fine). */
    fun removeClients(creds: SshCredentials, keys: Collection<WgKey>, protocol: Protocol = Protocol.AWG) {
        if (keys.isEmpty()) return
        SshConnection.open(creds, hostKeys).use { ssh ->
            for (k in keys) {
                val r = ssh.run(script("remove-peer.sh"), env = mapOf("CONTAINER" to protocol.amneziaContainer, "CLIENT_PUB" to k.toBase64()), timeoutMs = 60_000)
                if (r.exitCode != 0) throw SshException("remove_peer_failed", "Could not remove client: ${r.stderr.takeLast(2000)}")
            }
        }
    }

    fun removeClient(creds: SshCredentials, clientPublicKey: WgKey, protocol: Protocol = Protocol.AWG) {
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("remove-peer.sh"), env = mapOf("CONTAINER" to protocol.amneziaContainer, "CLIENT_PUB" to clientPublicKey.toBase64()))
            if (r.exitCode != 0) throw SshException("remove_peer_failed", "Could not remove client: ${r.stderr.takeLast(2000)}")
        }
    }

    /**
     * Co-administrators' SSH keys: [add] (OpenSSH public lines) are authorized for the login of
     * [creds], [remove] (public lines or bare base64 blobs) are taken out. One connection, idempotent.
     */
    fun updateAdminKeys(creds: SshCredentials, add: Collection<String>, remove: Collection<String> = emptyList()) {
        if (add.isEmpty() && remove.isEmpty()) return
        val blobs = remove.map { l -> l.trim().split(Regex("\\s+")).let { if (it.size >= 2) it[1] else it[0] } }.filter { it.length > 40 }
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("admin-keys.sh"), env = mapOf("PN_ADD" to add.joinToString("\n") { it.trim() }, "PN_DEL" to blobs.joinToString("\n")), timeoutMs = 60_000)
            if (r.exitCode != 0) throw SshException("admin_keys_failed", "Could not update administrator keys: ${r.stderr.takeLast(500)}")
        }
    }

    /**
     * Makes the server answer on [want] too (ports in use on the host are skipped; ports are only
     * added, never taken away) and reports what it answers on. Re-creates the container when a port
     * has to be added (seconds of downtime, rolled back on failure). Empty [want]: only reports.
     */
    fun ports(creds: SshCredentials, want: List<Int> = emptyList(), protocol: Protocol = Protocol.AWG): Ports =
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("ports.sh"), env = mapOf("CONTAINER" to protocol.amneziaContainer, "PN_PORTS" to want.joinToString(" ")), timeoutMs = 3 * 60_000)
            r.error?.let { throw SshException(it, "Ports: $it ${r.stderr.takeLast(500)}") }
            val main = r.results["port"]?.toIntOrNull() ?: throw SshException("ports_failed", "Ports: ${r.stderr.takeLast(500)}")
            val alt = r.results["alt_ports"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it != main }
            Ports(main, alt, r.results["ipv6"]?.trim()?.takeIf { ':' in it })
        }

    /** What the server is and does right now (info.sh); read-only, one connection, a second or two. */
    fun status(creds: SshCredentials, protocol: Protocol = Protocol.AWG): ServerStatus =
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("info.sh"), env = mapOf("CONTAINER" to protocol.amneziaContainer), timeoutMs = 60_000)
            ServerStatus.parse(r.results)
        }

    /**
     * Brings the server's AmneziaWG engine up to date (refresh.sh): when the latest
     * amneziavpn/amneziawg-go image has other binaries than the running container, the container is
     * re-created on a fresh image with everything it keeps (keys, clients, ports), rolled back on any
     * failure. Seconds of downtime, and only when there is something to update. True if it was refreshed.
     */
    fun refresh(creds: SshCredentials): Boolean =
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("refresh.sh"), env = mapOf("CONTAINER" to Protocol.AWG.amneziaContainer), timeoutMs = 10 * 60_000)
            r.error?.let { throw SshException(it, "Refresh: $it ${r.stderr.takeLast(500)}") }
            if (r.exitCode != 0) throw SshException("refresh_failed", "Refresh failed (${r.exitCode}): ${r.stderr.takeLast(500)}")
            r.results["refreshed"] == "1"
        }

    /**
     * Tunes the server for bulk traffic through the tunnel (tune.sh: TCP MSS clamping in the
     * container, socket buffers, backlog and BBR on the host, kept across reboots). Idempotent.
     * True when the clamping is in place.
     */
    fun tune(creds: SshCredentials, protocol: Protocol = Protocol.AWG): Boolean =
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("tune.sh"), env = mapOf("CONTAINER" to protocol.amneziaContainer), timeoutMs = 60_000)
            r.error?.let { throw SshException(it, "Tune: $it") }
            if (r.exitCode != 0) throw SshException("tune_failed", "Tune failed (${r.exitCode}): ${r.stderr.takeLast(500)}")
            r.results["mss"] == "ok"
        }

    /**
     * Reboots the whole server (forced, after syncing the disks). Returns as soon as the server has
     * taken the order; the reboot follows two seconds later, so the connection then drops.
     */
    fun reboot(creds: SshCredentials) {
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("reboot.sh"), timeoutMs = 30_000)
            r.error?.let { throw SshException(it, "Reboot refused: $it") }
            if (r.results["rebooting"] != "1") throw SshException("reboot_failed", "Reboot failed (${r.exitCode}): ${r.stderr.takeLast(500)}")
        }
    }

    companion object {
        private val random = SecureRandom()

        /** Same range AmneziaVPN picks from, away from well-known ports. */
        fun randomPort() = 30000 + random.nextInt(20000)

        /**
         * Spare ports for a server on [main]: 3478, where STUN and TURN live (coherent with the STUN
         * signature clients send), a random port from another range than [main], and 443, which
         * networks that let little through still allow.
         */
        fun altPortsFor(main: Int): List<Int> {
            val other = generateSequence { 10000 + random.nextInt(20000) }.first { it != main }
            return listOf(3478, other, 443).filter { it != main }
        }

        internal fun script(name: String): String =
            ServerManager::class.java.getResourceAsStream("/net/paramnezia/deploy/$name")!!.bufferedReader().use { it.readText().replace("\r\n", "\n") }

        /** `10.8.1.0` → `10.8.1.1`: the server takes the first address of its subnet. */
        internal fun firstHost(subnet: String): String {
            val p = IpPrefix.parse("$subnet/32")
            return IpPrefix.of(false, p.network + java.math.BigInteger.ONE, 32).address.hostAddress
        }

        /** Builds a client config from the server's [Interface] section and the add-peer results. */
        internal fun clientConfig(
            serverConf: String,
            results: Map<String, String>,
            clientPrivateKey: WgKey,
            host: String,
            dns: List<String>,
            mtu: Int,
            keepalive: String?,
        ): TunnelConfig {
            val lines = serverConf.lineSequence().map { it.trim() }
                .map { it.removePrefix("# ") } // I1–I5 are commented out on the server
                .filter { '=' in it }
                .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
            val awg = LinkedHashMap<AwgParam, String>()
            for (p in AwgParam.entries) lines[p.conf.lowercase()]?.takeIf { it.isNotEmpty() }?.let { awg[p] = it }
            // The server's own padding is no business of clients: ours choose per flow, AmneziaVPN keeps its trailers.
            awg.remove(AwgParam.CONTENT_PADDING_ADDITION)
            val port = lines["listenport"]?.toIntOrNull() ?: throw SshException("bad_server_config", "Server config has no ListenPort")
            val isAwg3 = AwgParam.HEADER_PROTECTION_KEY in awg
            val ip = results["client_ip"] ?: throw SshException("bad_server_reply", "Server did not return a client address")
            val ipv6 = results["client_ipv6"]?.let { IpPrefix.parse("$it/128") }
            val resolvers = if (ipv6 != null && dns == listOf("1.1.1.1", "1.0.0.1")) {
                listOf("2606:4700:4700::1111", "2606:4700:4700::1001") + dns
            } else dns
            val tunnel = TunnelConfig(
                TunnelConfig.Interface(
                    privateKey = clientPrivateKey,
                    addresses = listOf(IpPrefix.parse("$ip/32")) + listOfNotNull(ipv6),
                    dns = resolvers,
                    mtu = mtu,
                    awg = awg,
                ),
                listOf(
                    TunnelConfig.Peer(
                        publicKey = WgKey.fromBase64(results["server_pub"] ?: throw SshException("bad_server_reply", "No server key")),
                        presharedKey = results["psk"]?.takeIf { it.isNotBlank() }?.let(WgKey::fromBase64),
                        allowedIps = listOf(IpPrefix.ANY_V4, IpPrefix.ANY_V6),
                        endpoint = net.paramnezia.core.config.Uapi.formatEndpoint(host, port),
                        // A jittered keepalive is an AmneziaWG 3 feature; older peers need a plain number.
                        persistentKeepalive = keepalive ?: if (isAwg3) "25-35" else "25",
                    ),
                ),
            )
            // Validate by round-tripping through the parser every other client uses.
            return WgQuick.parse(WgQuick.write(tunnel))
        }
    }
}
