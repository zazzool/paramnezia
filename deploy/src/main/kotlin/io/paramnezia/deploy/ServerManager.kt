package io.paramnezia.deploy

import io.paramnezia.core.config.AwgParam
import io.paramnezia.core.config.TunnelConfig
import io.paramnezia.core.config.WgQuick
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.net.IpPrefix
import io.paramnezia.core.profile.Protocol
import io.paramnezia.core.profile.SshCredentials
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
    ): TunnelConfig = SshConnection.open(creds, hostKeys).use { ssh ->
        val r = ssh.run(
            script("add-peer.sh"),
            env = mapOf(
                "CONTAINER" to protocol.amneziaContainer,
                "CLIENT_PUB" to clientPrivateKey.publicKey().toBase64(),
                "CLIENT_NAME" to name,
            ) + (clientIp?.let { mapOf("CLIENT_IP" to it) } ?: emptyMap()),
        )
        r.error?.let { throw SshException(it, "Could not add client: $it") }
        if (r.exitCode != 0) throw SshException("add_peer_failed", "Could not add client: ${r.stderr.takeLast(2000)}")
        val serverConf = r.stdout.substringAfter("::conf-begin::\n", "").substringBefore("::conf-end::")
        clientConfig(serverConf, r.results, clientPrivateKey, creds.host, dns, mtu, keepalive)
    }

    fun removeClient(creds: SshCredentials, clientPublicKey: WgKey, protocol: Protocol = Protocol.AWG) {
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(script("remove-peer.sh"), env = mapOf("CONTAINER" to protocol.amneziaContainer, "CLIENT_PUB" to clientPublicKey.toBase64()))
            if (r.exitCode != 0) throw SshException("remove_peer_failed", "Could not remove client: ${r.stderr.takeLast(2000)}")
        }
    }

    companion object {
        private val random = SecureRandom()

        /** Same range AmneziaVPN picks from, away from well-known ports. */
        fun randomPort() = 30000 + random.nextInt(20000)

        internal fun script(name: String): String =
            ServerManager::class.java.getResourceAsStream("/io/paramnezia/deploy/$name")!!.bufferedReader().readText()

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
