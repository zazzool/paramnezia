package net.paramnezia.deploy.cloud

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a provider's location is: country, flag, part of the world, a Russian city name and how far
 * it is from Moscow. Providers name locations differently (Cloudzy by city, Vultr by IATA code,
 * Hetzner by `fsn1`, DigitalOcean by `ams3`), so every adapter maps through [place].
 */
object Geo {

    data class Place(val city: String, val country: String, val lat: Double, val lon: Double)

    enum class Area(val title: String) { EUROPE("Европа"), ASIA("Азия"), AMERICA("Америка"), OCEANIA("Океания"), AFRICA("Африка"), RUSSIA("Россия") }

    /** How fast a server there is likely to feel from Russia (by distance; real ping depends on the route). */
    enum class Reach(val title: String) { NEAR("быстро из России"), MID("средне"), FAR("далеко") }

    /** Known cities by lower-case English name, provider code or IATA code. */
    private val PLACES: Map<String, Place> = buildMap {
        fun p(keys: String, city: String, cc: String, lat: Double, lon: Double) {
            val place = Place(city, cc, lat, lon)
            for (k in keys.split('|')) put(k, place)
        }
        // Europe
        p("amsterdam|ams|ams1|ams2|ams3|nl-1|nl-ams|netherlands", "Амстердам", "NL", 52.37, 4.90)
        p("frankfurt|fra|fra1|de-1|de-fra|germany", "Франкфурт", "DE", 50.11, 8.68)
        p("falkenstein|fsn|fsn1", "Фалькенштайн", "DE", 50.48, 12.37)
        p("nuremberg|nürnberg|nbg|nbg1", "Нюрнберг", "DE", 49.45, 11.08)
        p("berlin|ber", "Берлин", "DE", 52.52, 13.40)
        p("helsinki|hel|hel1|finland", "Хельсинки", "FI", 60.17, 24.94)
        p("stockholm|sto|arn", "Стокгольм", "SE", 59.33, 18.07)
        p("london|lon|lon1|lhr|united kingdom|uk", "Лондон", "GB", 51.51, -0.13)
        p("manchester|man", "Манчестер", "GB", 53.48, -2.24)
        p("paris|par|cdg|france", "Париж", "FR", 48.86, 2.35)
        p("madrid|mad|spain", "Мадрид", "ES", 40.42, -3.70)
        p("milan|mil|mxp|italy", "Милан", "IT", 45.46, 9.19)
        p("warsaw|waw|poland", "Варшава", "PL", 52.23, 21.01)
        p("gdansk|gdańsk|pl-1|gdn", "Гданьск", "PL", 54.35, 18.65)
        p("bern|berne", "Берн", "CH", 46.95, 7.45)
        p("zurich|zürich|zrh|switzerland", "Цюрих", "CH", 47.38, 8.54)
        p("vienna|vie|austria", "Вена", "AT", 48.21, 16.37)
        p("prague|prg|czech", "Прага", "CZ", 50.08, 14.44)
        p("bucharest|otp|romania", "Бухарест", "RO", 44.43, 26.10)
        p("sofia|sof|bulgaria", "София", "BG", 42.70, 23.32)
        p("riga|rix|latvia", "Рига", "LV", 56.95, 24.11)
        p("tallinn|tll|estonia", "Таллин", "EE", 59.44, 24.75)
        p("vilnius|vno|lithuania", "Вильнюс", "LT", 54.69, 25.28)
        p("dublin|dub|ireland", "Дублин", "IE", 53.35, -6.26)
        p("brussels|bru|belgium", "Брюссель", "BE", 50.85, 4.35)
        p("oslo|osl|norway", "Осло", "NO", 59.91, 10.75)
        p("copenhagen|cph|denmark", "Копенгаген", "DK", 55.68, 12.57)
        p("lisbon|lis|portugal", "Лиссабон", "PT", 38.72, -9.14)
        p("istanbul|ist|turkey|türkiye", "Стамбул", "TR", 41.01, 28.98)
        p("belgrade|beg|serbia", "Белград", "RS", 44.79, 20.45)
        p("chisinau|kiv|moldova", "Кишинёв", "MD", 47.01, 28.86)
        p("tbilisi|tbs|georgia", "Тбилиси", "GE", 41.72, 44.79)
        p("yerevan|evn|armenia", "Ереван", "AM", 40.18, 44.51)
        // Russia and neighbours
        p("moscow|mow|svo|ru-3|msk|russia", "Москва", "RU", 55.76, 37.62)
        p("saint petersburg|st. petersburg|petersburg|spb|led|ru-1", "Санкт-Петербург", "RU", 59.93, 30.34)
        p("novosibirsk|ovb|ru-2", "Новосибирск", "RU", 55.01, 82.93)
        p("almaty|ala|kz-1|kazakhstan", "Алматы", "KZ", 43.24, 76.89)
        p("astana|nqz", "Астана", "KZ", 51.17, 71.45)
        p("tashkent|tas|uzbekistan", "Ташкент", "UZ", 41.30, 69.24)
        // Middle East, Asia
        p("dubai|dxb|uae|united arab emirates", "Дубай", "AE", 25.20, 55.27)
        p("tel aviv|tlv|israel", "Тель-Авив", "IL", 32.09, 34.78)
        p("mumbai|bom|india", "Мумбаи", "IN", 19.08, 72.88)
        p("bangalore|bengaluru|blr|blr1", "Бангалор", "IN", 12.97, 77.59)
        p("delhi|new delhi|del", "Дели", "IN", 28.61, 77.21)
        p("singapore|sgp|sgp1|sin", "Сингапур", "SG", 1.35, 103.82)
        p("hong kong|hkg", "Гонконг", "HK", 22.32, 114.17)
        p("tokyo|nrt|hnd|japan", "Токио", "JP", 35.68, 139.69)
        p("osaka|itm|kix", "Осака", "JP", 34.69, 135.50)
        p("seoul|icn|korea", "Сеул", "KR", 37.57, 126.98)
        p("jakarta|cgk|indonesia", "Джакарта", "ID", -6.21, 106.85)
        p("kuala lumpur|kul|malaysia", "Куала-Лумпур", "MY", 3.14, 101.69)
        p("bangkok|bkk|thailand", "Бангкок", "TH", 13.76, 100.50)
        p("taipei|tpe|taiwan", "Тайбэй", "TW", 25.03, 121.57)
        // Americas
        p("new york|new york city|nyc|nyc1|nyc2|nyc3|ewr|new jersey|piscataway", "Нью-Йорк", "US", 40.71, -74.01)
        p("ashburn|ash|virginia|iad", "Эшберн", "US", 39.04, -77.49)
        p("washington|was", "Вашингтон", "US", 38.91, -77.04)
        p("atlanta|atl", "Атланта", "US", 33.75, -84.39)
        p("miami|mia", "Майами", "US", 25.76, -80.19)
        p("chicago|ord|chi", "Чикаго", "US", 41.88, -87.63)
        p("dallas|dfw", "Даллас", "US", 32.78, -96.80)
        p("hillsboro|hil|oregon", "Хиллсборо", "US", 45.52, -122.99)
        p("seattle|sea", "Сиэтл", "US", 47.61, -122.33)
        p("san francisco|sfo|sfo1|sfo2|sfo3|silicon valley|sjc|san jose", "Сан-Франциско", "US", 37.77, -122.42)
        p("los angeles|lax", "Лос-Анджелес", "US", 34.05, -118.24)
        p("las vegas|las", "Лас-Вегас", "US", 36.17, -115.14)
        p("phoenix|phx", "Финикс", "US", 33.45, -112.07)
        p("denver|den", "Денвер", "US", 39.74, -104.99)
        p("salt lake city|slc", "Солт-Лейк-Сити", "US", 40.76, -111.89)
        p("honolulu|hnl", "Гонолулу", "US", 21.31, -157.86)
        p("toronto|tor|tor1|yto|yyz", "Торонто", "CA", 43.65, -79.38)
        p("montreal|yul", "Монреаль", "CA", 45.50, -73.57)
        p("vancouver|yvr", "Ванкувер", "CA", 49.28, -123.12)
        p("mexico city|mex|mexico", "Мехико", "MX", 19.43, -99.13)
        p("sao paulo|são paulo|sao|gru|brazil", "Сан-Паулу", "BR", -23.55, -46.63)
        p("santiago|scl|chile", "Сантьяго", "CL", -33.45, -70.67)
        // Oceania, Africa
        p("sydney|syd|syd1|australia", "Сидней", "AU", -33.87, 151.21)
        p("melbourne|mel", "Мельбурн", "AU", -37.81, 144.96)
        p("auckland|akl|new zealand", "Окленд", "NZ", -36.85, 174.76)
        p("johannesburg|jnb|south africa", "Йоханнесбург", "ZA", -26.20, 28.05)
        p("lagos|nigeria", "Лагос", "NG", 6.52, 3.38)
    }

    private val COUNTRY_NAMES = mapOf(
        "NL" to "Нидерланды", "DE" to "Германия", "FI" to "Финляндия", "SE" to "Швеция", "GB" to "Великобритания",
        "FR" to "Франция", "ES" to "Испания", "IT" to "Италия", "PL" to "Польша", "CH" to "Швейцария", "AT" to "Австрия",
        "CZ" to "Чехия", "RO" to "Румыния", "BG" to "Болгария", "LV" to "Латвия", "EE" to "Эстония", "LT" to "Литва",
        "IE" to "Ирландия", "BE" to "Бельгия", "NO" to "Норвегия", "DK" to "Дания", "PT" to "Португалия", "TR" to "Турция",
        "RS" to "Сербия", "MD" to "Молдова", "GE" to "Грузия", "AM" to "Армения", "RU" to "Россия", "KZ" to "Казахстан",
        "UZ" to "Узбекистан", "AE" to "ОАЭ", "IL" to "Израиль", "IN" to "Индия", "SG" to "Сингапур", "HK" to "Гонконг",
        "JP" to "Япония", "KR" to "Южная Корея", "ID" to "Индонезия", "MY" to "Малайзия", "TH" to "Таиланд", "TW" to "Тайвань",
        "US" to "США", "CA" to "Канада", "MX" to "Мексика", "BR" to "Бразилия", "CL" to "Чили", "AU" to "Австралия",
        "NZ" to "Новая Зеландия", "ZA" to "ЮАР", "NG" to "Нигерия",
    )

    private val ASIA = setOf("AE", "IL", "IN", "SG", "HK", "JP", "KR", "ID", "MY", "TH", "TW", "KZ", "UZ", "CN", "VN", "PH")
    private val AMERICA = setOf("US", "CA", "MX", "BR", "CL", "AR", "CO", "PE")
    private val OCEANIA = setOf("AU", "NZ")
    private val AFRICA = setOf("ZA", "NG", "EG", "KE", "MA")

    private const val MOSCOW_LAT = 55.76
    private const val MOSCOW_LON = 37.62

    /**
     * Finds a location from whatever the provider gave: a city ("New York City"), a code ("ams3",
     * "fsn1", "ewr"), or "City, CC". Tries every hint in order; unknown places get null.
     */
    fun place(vararg hints: String?): Place? {
        for (raw in hints) {
            val h = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
            PLACES[h]?.let { return it }
            // "Frankfurt, DE", "Amsterdam 3", "New York 1", "eu-central (Frankfurt)"
            val words = h.split(',', '(', ')', '/').map { it.trim() }.filter { it.isNotEmpty() }
            for (w in words) {
                PLACES[w]?.let { return it }
                PLACES[w.trimEnd { it.isDigit() || it == ' ' || it == '-' }]?.let { return it }
            }
            PLACES.entries.firstOrNull { (k, _) -> k.length > 3 && k in h }?.let { return it.value }
        }
        return null
    }

    /** Country code for [hints], or one that looks like a code itself ("NL"), or "??". */
    fun country(vararg hints: String?): String =
        place(*hints)?.country ?: hints.firstOrNull { it != null && it.length == 2 && it.all(Char::isLetter) }?.uppercase() ?: "??"

    fun countryName(cc: String): String = COUNTRY_NAMES[cc.uppercase()] ?: cc.uppercase()

    /** The emoji flag of a two-letter country code (🌐 when unknown). */
    fun flag(cc: String): String {
        val c = cc.uppercase()
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return "🌐"
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    fun area(cc: String): Area = when (cc.uppercase()) {
        "RU" -> Area.RUSSIA
        in ASIA -> Area.ASIA
        in AMERICA -> Area.AMERICA
        in OCEANIA -> Area.OCEANIA
        in AFRICA -> Area.AFRICA
        else -> Area.EUROPE
    }

    /** Great-circle distance from Moscow in km, or null when the place is unknown. */
    fun kmFromMoscow(p: Place?): Int? {
        p ?: return null
        val r = 6371.0
        val dLat = Math.toRadians(p.lat - MOSCOW_LAT)
        val dLon = Math.toRadians(p.lon - MOSCOW_LON)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(MOSCOW_LAT)) * cos(Math.toRadians(p.lat)) * sin(dLon / 2).pow(2)
        return (2 * r * asin(sqrt(a))).toInt()
    }

    fun reach(km: Int?): Reach? = when {
        km == null -> null
        km <= 2600 -> Reach.NEAR
        km <= 6000 -> Reach.MID
        else -> Reach.FAR
    }
}
