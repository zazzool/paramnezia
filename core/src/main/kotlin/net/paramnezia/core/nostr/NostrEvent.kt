package net.paramnezia.core.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** A NIP-01 event. */
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** Checks the id and the signature. Never trust relay input without this. */
    fun isValid(): Boolean = runCatching {
        id == computeId(pubkey, created_at, kind, tags, content) &&
            Schnorr.verify(pubkey.hex(), id.hex(), sig.hex())
    }.getOrDefault(false)

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * NIP-78 "arbitrary custom app data": parameterized replaceable, so relays keep only the
         * newest event per (author, d-tag). That is exactly a "latest manifest" slot.
         */
        const val KIND_APP_DATA = 30078

        private val json = Json { ignoreUnknownKeys = true }

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val arr = buildJsonArray {
                add(JsonPrimitive(0)); add(JsonPrimitive(pubkey)); add(JsonPrimitive(createdAt)); add(JsonPrimitive(kind))
                add(buildJsonArray { tags.forEach { t -> add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) } })
                add(JsonPrimitive(content))
            }
            // kotlinx compact output matches NIP-01 serialization (no spaces, minimal escaping).
            return MessageDigest.getInstance("SHA-256").digest(arr.toString().toByteArray()).toHex()
        }

        fun create(secret: ByteArray, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent {
            val pub = Schnorr.publicKey(secret).toHex()
            val id = computeId(pub, createdAt, kind, tags, content)
            return NostrEvent(id, pub, createdAt, kind, tags, content, Schnorr.sign(secret, id.hex()).toHex())
        }

        fun fromJson(text: String): NostrEvent? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /** ["EVENT", <event>] — what a client sends to publish. */
        fun publishFrame(e: NostrEvent): String = buildJsonArray { add(JsonPrimitive("EVENT")); add(Json.parseToJsonElement(e.toJson())) }.toString()

        /** ["REQ", subId, {authors, kinds, #d, limit}] for the newest app-data events. */
        fun requestFrame(subId: String, authors: List<String>, dTag: String, limit: Int = 10): String = buildJsonArray {
            add(JsonPrimitive("REQ")); add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("authors", JsonArray(authors.map(::JsonPrimitive)))
                put("kinds", JsonArray(listOf(JsonPrimitive(KIND_APP_DATA))))
                put("#d", JsonArray(listOf(JsonPrimitive(dTag))))
                put("limit", limit)
            })
        }.toString()

        fun closeFrame(subId: String): String = buildJsonArray { add(JsonPrimitive("CLOSE")); add(JsonPrimitive(subId)) }.toString()

        /** Parses relay frames: returns the event of an ["EVENT", subId, event] frame, "EOSE"/"OK" markers otherwise. */
        fun parseRelayFrame(text: String): RelayFrame = runCatching {
            val a = Json.parseToJsonElement(text).jsonArray
            when (a[0].jsonPrimitive.content) {
                "EVENT" -> RelayFrame.Event(a[1].jsonPrimitive.content, json.decodeFromJsonElement(serializer(), a[2].jsonObject))
                "EOSE" -> RelayFrame.EndOfStored(a[1].jsonPrimitive.content)
                "OK" -> RelayFrame.Ok(a[1].jsonPrimitive.content, a[2].jsonPrimitive.content.toBoolean())
                else -> RelayFrame.Other
            }
        }.getOrDefault(RelayFrame.Other)
    }
}

sealed interface RelayFrame {
    data class Event(val subId: String, val event: NostrEvent) : RelayFrame
    data class EndOfStored(val subId: String) : RelayFrame
    data class Ok(val eventId: String, val accepted: Boolean) : RelayFrame
    data object Other : RelayFrame
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.hex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
