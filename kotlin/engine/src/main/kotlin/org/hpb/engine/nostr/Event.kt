package org.hpb.engine.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.hpb.engine.Secp
import org.hpb.engine.hex
import org.hpb.engine.hexBytes
import org.hpb.engine.sha256

/** A NIP-01 event; identity keys are the same secp256k1 x-only keys as descriptors. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tagValue(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
}

object Events {
    private fun serializeForId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String = JsonArray(
        listOf(
            JsonPrimitive(0),
            JsonPrimitive(pubkey),
            JsonPrimitive(createdAt),
            JsonPrimitive(kind),
            JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }),
            JsonPrimitive(content),
        ),
    ).toString()

    fun sign(
        privkey: ByteArray,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): NostrEvent {
        val pubkey = Secp.xonlyHex(privkey)
        val id = sha256(serializeForId(pubkey, createdAt, kind, tags, content).toByteArray())
        return NostrEvent(
            id = id.hex(),
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = Secp.schnorrSign(id, privkey).hex(),
        )
    }

    fun verify(event: NostrEvent): Boolean {
        val id = sha256(
            serializeForId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
                .toByteArray(),
        )
        if (id.hex() != event.id) return false
        return runCatching {
            Secp.schnorrVerify(event.sig.hexBytes(), id, event.pubkey.hexBytes())
        }.getOrDefault(false)
    }

    fun toJson(event: NostrEvent): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(event.id),
            "pubkey" to JsonPrimitive(event.pubkey),
            "created_at" to JsonPrimitive(event.createdAt),
            "kind" to JsonPrimitive(event.kind),
            "tags" to JsonArray(event.tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }),
            "content" to JsonPrimitive(event.content),
            "sig" to JsonPrimitive(event.sig),
        ),
    )

    fun fromJson(json: JsonObject): NostrEvent = NostrEvent(
        id = json.getValue("id").jsonPrimitive.content,
        pubkey = json.getValue("pubkey").jsonPrimitive.content,
        createdAt = json.getValue("created_at").jsonPrimitive.long,
        kind = json.getValue("kind").jsonPrimitive.int,
        tags = json.getValue("tags").jsonArray.map { tag ->
            tag.jsonArray.map { it.jsonPrimitive.content }
        },
        content = json.getValue("content").jsonPrimitive.content,
        sig = json.getValue("sig").jsonPrimitive.content,
    )
}
