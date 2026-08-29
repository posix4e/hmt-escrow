package org.hpb.engine.nostr

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.Secp
import org.hpb.engine.hex
import org.hpb.engine.hexBytes

/** The official NIP-44 v2 test vectors, vendored at docs/vectors/. */
class Nip44VectorsTest {
    private val vectors = Json.parseToJsonElement(
        Files.readString(Path.of("../../docs/vectors/nip44.vectors.json")),
    ).jsonObject.getValue("v2").jsonObject

    private val valid = vectors.getValue("valid").jsonObject
    private val invalid = vectors.getValue("invalid").jsonObject

    @Test
    fun conversationKeys() {
        for (case in valid.getValue("get_conversation_key").jsonArray) {
            val obj = case.jsonObject
            val key = Nip44.conversationKey(
                obj.getValue("sec1").jsonPrimitive.content.hexBytes(),
                obj.getValue("pub2").jsonPrimitive.content.hexBytes(),
            )
            assertEquals(obj.getValue("conversation_key").jsonPrimitive.content, key.hex())
        }
    }

    @Test
    fun messageKeys() {
        val section = valid.getValue("get_message_keys").jsonObject
        val conversationKey =
            section.getValue("conversation_key").jsonPrimitive.content.hexBytes()
        for (case in section.getValue("keys").jsonArray) {
            val obj = case.jsonObject
            val keys = Nip44.messageKeys(
                conversationKey, obj.getValue("nonce").jsonPrimitive.content.hexBytes(),
            )
            assertEquals(obj.getValue("chacha_key").jsonPrimitive.content, keys.chachaKey.hex())
            assertEquals(obj.getValue("chacha_nonce").jsonPrimitive.content, keys.chachaNonce.hex())
            assertEquals(obj.getValue("hmac_key").jsonPrimitive.content, keys.hmacKey.hex())
        }
    }

    @Test
    fun paddedLengths() {
        for (case in valid.getValue("calc_padded_len").jsonArray) {
            val pair = case.jsonArray
            assertEquals(pair[1].jsonPrimitive.int, Nip44.paddedLength(pair[0].jsonPrimitive.int))
        }
    }

    @Test
    fun encryptDecryptRoundTrips() {
        for (case in valid.getValue("encrypt_decrypt").jsonArray) {
            val obj = case.jsonObject
            val sec1 = obj.getValue("sec1").jsonPrimitive.content.hexBytes()
            val sec2 = obj.getValue("sec2").jsonPrimitive.content.hexBytes()
            val conversationKey =
                obj.getValue("conversation_key").jsonPrimitive.content.hexBytes()
            assertEquals(
                conversationKey.hex(),
                Nip44.conversationKey(sec1, Secp.xonly(sec2)).hex(),
                "conversation key derivation",
            )
            val payload = Nip44.encrypt(
                obj.getValue("plaintext").jsonPrimitive.content,
                conversationKey,
                obj.getValue("nonce").jsonPrimitive.content.hexBytes(),
            )
            assertEquals(obj.getValue("payload").jsonPrimitive.content, payload)
            assertEquals(
                obj.getValue("plaintext").jsonPrimitive.content,
                Nip44.decrypt(payload, conversationKey),
            )
        }
    }

    @Test
    fun invalidDecryptsFail() {
        for (case in invalid.getValue("decrypt").jsonArray) {
            val obj = case.jsonObject
            assertFails(obj.getValue("note").jsonPrimitive.content) {
                Nip44.decrypt(
                    obj.getValue("payload").jsonPrimitive.content,
                    obj.getValue("conversation_key").jsonPrimitive.content.hexBytes(),
                )
            }
        }
    }

    @Test
    fun invalidConversationKeysFail() {
        for (case in invalid.getValue("get_conversation_key").jsonArray) {
            val obj = case.jsonObject
            assertFails(obj["note"]?.jsonPrimitive?.content ?: "invalid key") {
                Nip44.conversationKey(
                    obj.getValue("sec1").jsonPrimitive.content.hexBytes(),
                    obj.getValue("pub2").jsonPrimitive.content.hexBytes(),
                )
            }
        }
    }

    @Test
    fun eventSignVerifyRoundTrip() {
        val key = ByteArray(32).also { it[31] = 7 }
        val event = Events.sign(key, 30078, listOf(listOf("d", "test")), "hello", createdAt = 42)
        assertTrue(Events.verify(event))
        assertTrue(!Events.verify(event.copy(content = "tampered")))
        assertTrue(!Events.verify(event.copy(sig = "00".repeat(64))))
    }
}
