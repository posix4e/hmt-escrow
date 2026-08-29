package org.hpb.harness

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hpb.engine.Secp
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.Kinds
import org.hpb.engine.nostr.KvStore
import org.hpb.engine.nostr.NostrClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * K4 proof: the KVStore rides NIP-78 addressable events on a real
 * (independent, signature-validating) relay — latest-wins, verified reads,
 * tamper rejection at the relay boundary.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KvStoreTest {
    private val relay = RelayFixture.start()
    private val client = NostrClient(listOf(relay.url))
    private val kv = KvStore(client)

    private val operatorKey = ByteArray(32).also { it[30] = 0x40; it[31] = 0x01 }
    private val operator = Secp.xonlyHex(operatorKey)

    @AfterAll
    fun tearDown() {
        client.close()
        relay.close()
    }

    @Test
    fun setGetAndLatestWins() {
        assertTrue(kv.set(operatorKey, "fee", "5"))
        assertEquals("5", kv.get(operator, "fee"))
        assertEquals(5, kv.feePct(operator))

        Thread.sleep(1100) // distinct created_at so latest-wins is unambiguous
        assertTrue(kv.set(operatorKey, "fee", "7"))
        assertEquals(7, kv.feePct(operator))

        assertNull(kv.get(operator, "missing-key"))
    }

    @Test
    fun relayRejectsTamperedEvents() {
        val honest = Events.sign(operatorKey, Kinds.KV, listOf(listOf("d", "x")), "value")
        val tampered = honest.copy(content = "evil")
        assertFalse(client.publish(tampered), "independent relay must reject a bad signature")
    }

    @Test
    fun multiRelayFetchDeduplicates() {
        assertTrue(kv.set(operatorKey, "role", "witness"))
        val union = NostrClient(listOf(relay.url, relay.url)).use { doubled ->
            KvStore(doubled).get(operator, "role")
        }
        assertEquals("witness", union)
    }
}
