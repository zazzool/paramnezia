package net.paramnezia.core.config

/**
 * AmneziaWG obfuscation and timing parameters, in the order AmneziaVPN writes them.
 * [conf] is the wg-quick key (also the AmneziaVPN JSON key), [uapi] the amneziawg-go IPC key.
 */
enum class AwgParam(val conf: String, val uapi: String, val kind: Kind) {
    JC("Jc", "jc", Kind.INT),
    JMIN("Jmin", "jmin", Kind.INT),
    JMAX("Jmax", "jmax", Kind.INT),
    S1("S1", "s1", Kind.INT),
    S2("S2", "s2", Kind.INT),
    S3("S3", "s3", Kind.INT),
    S4("S4", "s4", Kind.INT),
    H1("H1", "h1", Kind.RANGE),
    H2("H2", "h2", Kind.RANGE),
    H3("H3", "h3", Kind.RANGE),
    H4("H4", "h4", Kind.RANGE),
    I1("I1", "i1", Kind.TEXT),
    I2("I2", "i2", Kind.TEXT),
    I3("I3", "i3", Kind.TEXT),
    I4("I4", "i4", Kind.TEXT),
    I5("I5", "i5", Kind.TEXT),
    HEADER_PROTECTION_KEY("HeaderProtectionKey", "header_protection_key", Kind.KEY),
    CONTENT_PADDING_ADDITION("ContentPaddingAddition", "content_padding_addition", Kind.RANGE),
    REKEY_AFTER_TIME("RekeyAfterTime", "rekey_after_time", Kind.RANGE),
    REKEY_TIMEOUT("RekeyTimeout", "rekey_timeout", Kind.RANGE),
    REJECT_AFTER_TIME("RejectAfterTime", "reject_after_time", Kind.RANGE),
    KEEPALIVE_TIMEOUT("KeepaliveTimeout", "keepalive_timeout", Kind.RANGE),
    MAX_HANDSHAKE_ATTEMPTS("MaxHandshakeAttempts", "max_handshake_attempts", Kind.RANGE),
    RANDOM_TRAILERS("RandomTrailers", "random_trailers", Kind.BOOL),
    DISABLE_COOKIES("DisableCookies", "disable_cookies", Kind.BOOL);

    enum class Kind { INT, RANGE, TEXT, KEY, BOOL }

    companion object {
        private val byLowerConf = entries.associateBy { it.conf.lowercase() }

        fun fromConfKey(key: String): AwgParam? = byLowerConf[key.lowercase()]

        /** amneziawg-go uses strconv.ParseBool, while AmneziaVPN writes "on"/"off". */
        fun toBool(value: String): Boolean? = when (value.trim().lowercase()) {
            "on", "true", "t", "yes", "1" -> true
            "off", "false", "f", "no", "0" -> false
            else -> null
        }
    }
}
