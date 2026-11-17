package net.paramnezia.core.config

/**
 * What a connection is made of, in words people can read, and the rules for changing it later.
 *
 * Every AmneziaWG parameter has a [ParamSpec]: a name, the group it is shown in, who has to agree
 * on it ([Scope]) and what a valid value looks like ([Editor]). The details screen shows configs
 * through this catalog today; an editor will use the same specs (labels, validation, and whether a
 * change concerns only this device or the server and every client of it), so the two never drift.
 */
object ParamCatalog {

    /** Who has to agree on a value. */
    enum class Scope(val label: String) {
        /** The server checks it: changing it means the server and every one of its clients. */
        SERVER("сервер"),
        /** Only this device's own packets: each device may use its own. */
        CLIENT("устройство"),
    }

    /** The kind of value, for display and for an editor's validation. */
    sealed interface Editor {
        /** A whole number in [min]..[max]. */
        data class Number(val min: Long, val max: Long) : Editor
        /** A number or a range `a-b` within [min]..[max]. */
        data class Range(val min: Long, val max: Long) : Editor
        /** On / off. */
        data object Toggle : Editor
        /** A 32-byte key, base64. */
        data object Key : Editor
        /** An AmneziaWG packet template (`<b 0x…><r 12>`…). */
        data object Template : Editor

        /** Null when [value] is acceptable, else what is wrong with it. */
        fun check(value: String): String? {
            val v = value.trim()
            return when (this) {
                is Number -> {
                    val n = v.toLongOrNull()
                    when {
                        n == null -> "нужно число"
                        n !in min..max -> "от $min до $max"
                        else -> null
                    }
                }
                is Range -> {
                    val parts = v.split('-').map { it.trim().toLongOrNull() }
                    when {
                        parts.size !in 1..2 || parts.any { it == null } -> "число или диапазон «a-b»"
                        parts.any { it!! !in min..max } -> "от $min до $max"
                        parts.size == 2 && parts[0]!! > parts[1]!! -> "начало диапазона больше конца"
                        else -> null
                    }
                }
                Toggle -> if (AwgParam.toBool(v) != null) null else "on или off"
                Key -> if (runCatching { java.util.Base64.getDecoder().decode(v).size == 32 }.getOrDefault(false)) null else "ключ из 32 байт (base64)"
                Template -> if (Signatures.length(v) != null) null else "шаблон вида <b 0x…><r 12>"
            }
        }
    }

    enum class Group(val title: String) {
        HANDSHAKE("Маскировка рукопожатия"),
        SIGNATURES("Подписи первых пакетов"),
        TRAFFIC("Пакеты данных"),
        TIMERS("Тайминги"),
    }

    data class ParamSpec(
        val param: AwgParam,
        val label: String,
        val group: Group,
        val scope: Scope,
        val editor: Editor,
        /** One line on what it does. */
        val hint: String,
    )

    private const val U32 = 4_294_967_295L

    val all: List<ParamSpec> = listOf(
        ParamSpec(AwgParam.JC, "Мусорные пакеты", Group.HANDSHAKE, Scope.CLIENT, Editor.Number(0, 128), "Сколько случайных пакетов уходит перед рукопожатием"),
        ParamSpec(AwgParam.JMIN, "Мусор: от, байт", Group.HANDSHAKE, Scope.CLIENT, Editor.Number(0, 1280), "Наименьший размер мусорного пакета"),
        ParamSpec(AwgParam.JMAX, "Мусор: до, байт", Group.HANDSHAKE, Scope.CLIENT, Editor.Number(0, 1280), "Наибольший размер мусорного пакета"),
        ParamSpec(AwgParam.S1, "Паддинг инициации", Group.HANDSHAKE, Scope.SERVER, Editor.Number(0, 1132), "Случайные байты перед первым пакетом рукопожатия"),
        ParamSpec(AwgParam.S2, "Паддинг ответа", Group.HANDSHAKE, Scope.SERVER, Editor.Number(0, 1188), "Случайные байты перед ответом сервера"),
        ParamSpec(AwgParam.S3, "Паддинг cookie", Group.HANDSHAKE, Scope.SERVER, Editor.Number(0, 1216), "Случайные байты перед cookie-ответом"),
        ParamSpec(AwgParam.S4, "Паддинг данных", Group.TRAFFIC, Scope.SERVER, Editor.Number(0, 1248), "Случайные байты перед каждым пакетом данных"),
        ParamSpec(AwgParam.H1, "Заголовок инициации", Group.HANDSHAKE, Scope.SERVER, Editor.Range(1, U32), "Тип первого пакета рукопожатия (число или диапазон)"),
        ParamSpec(AwgParam.H2, "Заголовок ответа", Group.HANDSHAKE, Scope.SERVER, Editor.Range(1, U32), "Тип ответа сервера"),
        ParamSpec(AwgParam.H3, "Заголовок cookie", Group.HANDSHAKE, Scope.SERVER, Editor.Range(1, U32), "Тип cookie-ответа"),
        ParamSpec(AwgParam.H4, "Заголовок данных", Group.TRAFFIC, Scope.SERVER, Editor.Range(1, U32), "Тип пакета данных"),
        ParamSpec(AwgParam.HEADER_PROTECTION_KEY, "Защита заголовков", Group.HANDSHAKE, Scope.SERVER, Editor.Key, "Заголовки пакетов шифруются (AmneziaWG 3)"),
        ParamSpec(AwgParam.I1, "Подпись I1", Group.SIGNATURES, Scope.CLIENT, Editor.Template, "Пакет перед каждым рукопожатием"),
        ParamSpec(AwgParam.I2, "Подпись I2", Group.SIGNATURES, Scope.CLIENT, Editor.Template, "Второй такой пакет"),
        ParamSpec(AwgParam.I3, "Подпись I3", Group.SIGNATURES, Scope.CLIENT, Editor.Template, "Третий"),
        ParamSpec(AwgParam.I4, "Подпись I4", Group.SIGNATURES, Scope.CLIENT, Editor.Template, "Четвёртый"),
        ParamSpec(AwgParam.I5, "Подпись I5", Group.SIGNATURES, Scope.CLIENT, Editor.Template, "Пятый"),
        ParamSpec(AwgParam.RANDOM_TRAILERS, "Случайные хвосты", Group.TRAFFIC, Scope.SERVER, Editor.Toggle, "Пакеты дополняются случайной длиной"),
        ParamSpec(AwgParam.CONTENT_PADDING_ADDITION, "Паддинг содержимого", Group.TRAFFIC, Scope.CLIENT, Editor.Range(0, 1280), "Нули внутри шифрованного пакета; заменяет хвосты"),
        ParamSpec(AwgParam.DISABLE_COOKIES, "Без cookie", Group.HANDSHAKE, Scope.SERVER, Editor.Toggle, "Сервер не отвечает cookie под нагрузкой"),
        ParamSpec(AwgParam.REKEY_AFTER_TIME, "Смена ключей, с", Group.TIMERS, Scope.CLIENT, Editor.Range(10, 86_400), "Как часто сессия получает новые ключи"),
        ParamSpec(AwgParam.REKEY_TIMEOUT, "Повтор рукопожатия, с", Group.TIMERS, Scope.CLIENT, Editor.Range(1, 600), "Пауза перед повтором неответившего рукопожатия"),
        ParamSpec(AwgParam.REJECT_AFTER_TIME, "Срок ключей, с", Group.TIMERS, Scope.CLIENT, Editor.Range(10, 86_400), "Дольше этого старые ключи не принимаются"),
        ParamSpec(AwgParam.KEEPALIVE_TIMEOUT, "Ожидание keepalive, с", Group.TIMERS, Scope.CLIENT, Editor.Range(1, 600), "Когда отвечать пустым пакетом"),
        ParamSpec(AwgParam.MAX_HANDSHAKE_ATTEMPTS, "Попыток рукопожатия", Group.TIMERS, Scope.CLIENT, Editor.Range(1, 1000), "Сколько раз пробовать до паузы"),
    )

    private val byParam = all.associateBy { it.param }

    fun spec(p: AwgParam): ParamSpec = byParam.getValue(p)

    /** [value] of [p] in words: a decoded signature, a key shortened, a toggle as вкл/выкл. */
    fun describe(p: AwgParam, value: String): String = when (spec(p).editor) {
        ParamCatalog.Editor.Template -> Signatures.name(value)
        ParamCatalog.Editor.Toggle -> if (AwgParam.toBool(value) == true) "вкл." else "выкл."
        ParamCatalog.Editor.Key -> "вкл."
        else -> value.trim()
    }

    /** The parameters [config] sets, by group, in catalog order. */
    fun of(config: TunnelConfig): Map<Group, List<Pair<ParamSpec, String>>> =
        all.mapNotNull { s -> config.iface.awg[s.param]?.takeIf { it.isNotBlank() }?.let { s to it } }.groupBy { it.first.group }
}

/** Which flavour of the protocol a config speaks. */
enum class ProtocolVersion(val label: String) {
    WIREGUARD("WireGuard"),
    AWG_1("AmneziaWG 1.0"),
    AWG_1_5("AmneziaWG 1.5"),
    AWG_2("AmneziaWG 2.0"),
    AWG_3("AmneziaWG 3");

    companion object {
        /**
         * From the parameters a config carries: header protection, random trailers or jittered
         * timers are AmneziaWG 3; header ranges or the cookie and data paddings (S3, S4) are 2.0;
         * signature packets on top of 1.0 are 1.5; junk, paddings or custom headers are 1.0.
         */
        fun of(config: TunnelConfig): ProtocolVersion {
            val a = config.iface.awg
            fun set(p: AwgParam) = a[p]?.trim()?.let { it.isNotEmpty() && it != "0" } == true
            val v3 = listOf(AwgParam.HEADER_PROTECTION_KEY, AwgParam.RANDOM_TRAILERS, AwgParam.CONTENT_PADDING_ADDITION, AwgParam.REKEY_AFTER_TIME,
                AwgParam.REKEY_TIMEOUT, AwgParam.REJECT_AFTER_TIME, AwgParam.KEEPALIVE_TIMEOUT, AwgParam.MAX_HANDSHAKE_ATTEMPTS, AwgParam.DISABLE_COOKIES)
            if (v3.any(::set)) return AWG_3
            val ranges = listOf(AwgParam.H1, AwgParam.H2, AwgParam.H3, AwgParam.H4).any { a[it]?.contains('-') == true }
            if (ranges || set(AwgParam.S3) || set(AwgParam.S4)) return AWG_2
            val signatures = listOf(AwgParam.I1, AwgParam.I2, AwgParam.I3, AwgParam.I4, AwgParam.I5).any { !a[it].isNullOrBlank() }
            val headers = listOf(AwgParam.H1, AwgParam.H2, AwgParam.H3, AwgParam.H4).withIndex().any { (i, p) -> a[p]?.trim()?.let { it.isNotEmpty() && it != "${i + 1}" } == true }
            val v1 = set(AwgParam.JC) || set(AwgParam.S1) || set(AwgParam.S2) || headers
            return when {
                signatures -> AWG_1_5
                v1 -> AWG_1
                else -> WIREGUARD
            }
        }
    }
}

/** Reading AmneziaWG packet templates (`<b 0x…>`, `<r n>`, `<rc n>`, `<rd n>`, `<t>`, `<c>`). */
object Signatures {
    private val tag = Regex("<\\s*(\\w+)\\s*([^>]*)>")

    /** Bytes the template produces, or null if it is not one. */
    fun length(spec: String): Int? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        var n = 0
        var at = 0
        for (m in tag.findAll(s)) {
            if (s.substring(at, m.range.first).isNotBlank()) return null
            at = m.range.last + 1
            val (name, arg) = m.destructured
            n += when (name) {
                "b" -> arg.trim().removePrefix("0x").let { h -> if (h.length % 2 != 0 || h.any { it !in "0123456789abcdefABCDEF" }) return null else h.length / 2 }
                "r", "rc", "rd" -> arg.trim().toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
                "t", "c" -> 4
                else -> return null
            }
        }
        return if (s.substring(at).isBlank() && n > 0) n else null
    }

    /** What a template imitates, as far as one can tell from its fixed bytes. */
    fun name(spec: String): String {
        val s = spec.trim()
        val first = Regex("<b\\s+0x([0-9a-fA-F]+)>").find(s)?.groupValues?.get(1)?.lowercase().orEmpty()
        val len = length(s)
        val bytes = len?.let { " · $it Б" }.orEmpty()
        return when {
            s == Camouflage.STUN_BINDING -> "STUN Binding Request$bytes"
            first.startsWith("16feff") || first.startsWith("16fefd") -> "DTLS ClientHello (WebRTC)$bytes"
            first.startsWith("000100002112a442") -> "STUN$bytes"
            first.startsWith("8580") || first.startsWith("8180") -> "DNS-ответ$bytes"
            first.startsWith("0100") && s.startsWith("<r 2>") -> "DNS-запрос$bytes"
            first.startsWith("c") && first.length >= 10 && first.substring(2, 10) == "00000001" -> "QUIC Initial$bytes"
            len == null -> "неизвестный формат"
            else -> "свои байты$bytes"
        }
    }
}
