package net.paramnezia.core.profile

/**
 * Server access pasted in one go. People rarely type an IP and a password: they copy the hoster's
 * whole e-mail ("IP: 1.2.3.4  Логин: root  Пароль: …"), an `ssh -p 2222 root@host` line from a
 * panel, a `root@host:port` string or a private key. [parse] finds whatever of these is there, so
 * one tap of «Вставить» fills every field it can.
 */
object SshPaste {
    data class Found(
        val host: String? = null,
        val port: Int? = null,
        val user: String? = null,
        val password: String? = null,
        val privateKey: String? = null,
    ) {
        val isEmpty: Boolean get() = host == null && port == null && user == null && password == null && privateKey == null
    }

    private val PEM = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----")
    private val IPV4 = Regex("(?<![\\d.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?![\\d.])")
    private val HOSTNAME = Regex("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$")
    private val IPV6 = Regex("^\\[?[0-9A-Fa-f:]{2,39}]?$")

    // Labels hosters use in their e-mails and panels, Russian and English. Order matters: the more
    // specific label first ("ssh port" before "port").
    private val HOST_LABELS = listOf("ip-адрес", "ip адрес", "ipv4", "ip address", "ip", "адрес сервера", "адрес", "хост", "host", "hostname", "server", "сервер")
    private val USER_LABELS = listOf("имя пользователя", "пользователь", "логин", "login", "username", "user")
    private val PASS_LABELS = listOf("root password", "root-пароль", "пароль root", "пароль", "password", "pass", "pwd")
    private val PORT_LABELS = listOf("ssh port", "ssh-порт", "порт ssh", "порт", "port")

    fun parse(text: String): Found {
        val raw = text.trim()
        if (raw.isEmpty()) return Found()
        PEM.find(raw)?.let { m ->
            val rest = parse(raw.removeRange(m.range))
            return rest.copy(privateKey = m.value.trim())
        }
        var found = Found()

        // "ssh -p 2222 root@host" or "ssh root@host -p 2222".
        Regex("(?:^|\\s)ssh\\s+(.+)$", RegexOption.MULTILINE).find(raw)?.let { m ->
            val args = m.groupValues[1].trim().split(Regex("\\s+"))
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-p" && i + 1 < args.size -> { found = found.copy(port = args[i + 1].toIntOrNull()); i++ }
                    a.startsWith("-p") && a.length > 2 -> found = found.copy(port = a.substring(2).toIntOrNull())
                    a == "-l" && i + 1 < args.size -> { found = found.copy(user = args[i + 1]); i++ }
                    a.startsWith("-") -> if (a in OPTIONS_WITH_VALUE && i + 1 < args.size) i++
                    else -> hostSpec(a)?.let { (u, h, p) ->
                        found = found.copy(host = found.host ?: h, user = u ?: found.user, port = p ?: found.port)
                    }
                }
                i++
            }
        }

        // "Label: value" lines.
        for (line in raw.lines()) {
            val (label, value) = splitLabel(line) ?: continue
            if ("mail" in label || "почт" in label || "панел" in label || "panel" in label || "url" in label) continue
            when {
                PORT_LABELS.any { label == it || label.endsWith(" $it") } ->
                    value.trim().takeWhile(Char::isDigit).toIntOrNull()?.takeIf { it in 1..65535 }?.let { if (found.port == null) found = found.copy(port = it) }
                PASS_LABELS.any { label == it || label.startsWith("$it ") || label.endsWith(" $it") } ->
                    if (found.password == null && value.isNotBlank()) found = found.copy(password = value.trim())
                USER_LABELS.any { label == it || label.endsWith(" $it") } ->
                    if (found.user == null) value.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }?.let { found = found.copy(user = it) }
                HOST_LABELS.any { label == it || label.startsWith("$it ") || label.endsWith(" $it") } ->
                    if (found.host == null) hostSpec(value.trim().split(Regex("\\s+")).first())?.let { (u, h, p) ->
                        found = found.copy(host = h, user = found.user ?: u, port = found.port ?: p)
                    }
            }
        }

        // A bare "root@host:port", "host:port" or "host" (a single token).
        if (found.host == null) {
            val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size == 1) hostSpec(tokens[0])?.let { (u, h, p) -> found = found.copy(host = h, user = found.user ?: u, port = found.port ?: p) }
        }
        // Anything else: the first IPv4 address in the text.
        if (found.host == null) IPV4.find(raw)?.let { found = found.copy(host = it.value) }
        return found
    }

    private val OPTIONS_WITH_VALUE = setOf("-i", "-o", "-F", "-J", "-L", "-R", "-D", "-b", "-c", "-E", "-m", "-W", "-w", "-O", "-Q", "-S", "-B", "-e")

    /** "label: value", "label = value" or "label<tab>value"; label lowercased, without bullets. */
    private fun splitLabel(line: String): Pair<String, String>? {
        val l = line.trim().trimStart('-', '*', '•', '·', ' ')
        val m = Regex("^([\\p{L}][\\p{L}0-9 ._()/-]{0,40}?)\\s*(?::|=|\\t|\\s—\\s|\\s-\\s)\\s*(.+)$").find(l) ?: return null
        val label = m.groupValues[1].lowercase().replace(Regex("\\(.*?\\)"), "").replace('_', ' ').trim()
        return label to m.groupValues[2].trim()
    }

    /** "user@host:port" in any combination; the host must look like an IP or a domain name. */
    private fun hostSpec(s: String): Triple<String?, String, Int?>? {
        var rest = s.trim().trim(',', ';', '.', '"', '\'', '`', '<', '>')
        if (rest.isEmpty()) return null
        rest = rest.removePrefix("ssh://")
        var user: String? = null
        val at = rest.lastIndexOf('@')
        if (at > 0) { user = rest.substring(0, at); rest = rest.substring(at + 1) }
        var port: Int? = null
        val bracket = Regex("^\\[([0-9A-Fa-f:]+)](?::(\\d+))?$").find(rest)
        val host: String
        if (bracket != null) {
            host = bracket.groupValues[1]; port = bracket.groupValues[2].toIntOrNull()
        } else if (rest.count { it == ':' } == 1) {
            host = rest.substringBefore(':'); port = rest.substringAfter(':').toIntOrNull() ?: return null
        } else host = rest
        val ok = IPV4.matches(host) || HOSTNAME.matches(host) || (host.count { it == ':' } >= 2 && IPV6.matches(host))
        if (!ok) return null
        if (port != null && port !in 1..65535) return null
        return Triple(user, host, port)
    }

    /** True for text that is an SSH private key rather than a password. */
    fun isPrivateKey(text: String): Boolean = text.trimStart().startsWith("-----BEGIN") && "PRIVATE KEY" in text

    /** Short description of a key for the UI ("SSH-ключ OpenSSH"), never the key itself. */
    fun describeKey(text: String): String {
        val kind = Regex("-----BEGIN ([A-Z0-9 ]*?)\\s*PRIVATE KEY-----").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return when (kind) {
            "OPENSSH" -> "SSH-ключ OpenSSH"
            "RSA" -> "SSH-ключ RSA"
            "EC" -> "SSH-ключ ECDSA"
            "" -> "SSH-ключ"
            else -> "SSH-ключ $kind"
        }
    }
}
