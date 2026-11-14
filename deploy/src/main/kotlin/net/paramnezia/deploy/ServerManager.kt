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
    )

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
                ),
            ) { line ->
                if (line.startsWith("::step::")) {
                    runCatching { Step.valueOf(line.removePrefix("::step::").uppercase()) }.getOrNull()?.let(onStep)
                }
            }
            r.error?.let { throw SshException(it, "Install failed: $it\n${r.stderr.takeLast(2000)}") }
            if (r.exitCode != 0) throw SshException("install_failed", "Install failed (${r.exitCode}): ${r.stderr.takeLast(2000)}")
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
    ): TunnelConfig {
        val r = registerPeer(creds, clientPrivateKey.publicKey(), name, protocol, clientIp)
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
    ): PeerRegistration = SshConnection.open(creds, hostKeys).use { ssh ->
        val r = ssh.run(
            script("add-peer.sh"),
            env = mapOf(
                "CONTAINER" to protocol.amneziaContainer,
                "CLIENT_PUB" to publicKey.toBase64(),
                "CLIENT_NAME" to name,
            ) + (clientIp?.let { mapOf("CLIENT_IP" to it) } ?: emptyMap()),
        )
        r.error?.let { throw SshException(it, "Could not add client: $it") }
        if (r.exitCode != 0) throw SshException("add_peer_failed", "Could not add client: ${r.stderr.takeLast(2000)}")
        PeerRegistration(r.stdout.substringAfter("::conf-begin::\n", "").substringBefore("::conf-end::"), r.results)
    }

    /** A peer to make sure of: public key, name for clientsTable, fixed address (or null). */
    data class PeerSpec(val publicKey: WgKey, val name: String, val clientIp: String?)

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
                    ssh.run(
                        script("add-peer.sh"),
                        env = mapOf("CONTAINER" to protocol.amneziaContainer, "CLIENT_PUB" to p.publicKey.toBase64(), "CLIENT_NAME" to p.name) +
                            (p.clientIp?.let { mapOf("CLIENT_IP" to it) } ?: emptyMap()),
                        timeoutMs = 60_000,
                    )
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

        internal fun script(name: String): String =
            ServerManager::class.java.getResourceAsStream("/net/paramnezia/deploy/$name")!!.bufferedReader().readText()

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
            val port = lines["listenport"]?.toIntOrNull() ?: throw SshException("bad_server_config", "Server config has no ListenPort")
            val isAwg3 = AwgParam.HEADER_PROTECTION_KEY in awg
            val endpointHost = if (host.contains(':')) "[$host]" else host
            val ip = results["client_ip"] ?: throw SshException("bad_server_reply", "Server did not return a client address")
            val tunnel = TunnelConfig(
                TunnelConfig.Interface(
                    privateKey = clientPrivateKey,
                    addresses = listOf(IpPrefix.parse("$ip/32")),
                    dns = dns,
                    mtu = mtu,
                    awg = awg,
                ),
                listOf(
                    TunnelConfig.Peer(
                        publicKey = WgKey.fromBase64(results["server_pub"] ?: throw SshException("bad_server_reply", "No server key")),
                        presharedKey = results["psk"]?.takeIf { it.isNotBlank() }?.let(WgKey::fromBase64),
                        allowedIps = listOf(IpPrefix.ANY_V4, IpPrefix.ANY_V6),
                        endpoint = "$endpointHost:$port",
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
