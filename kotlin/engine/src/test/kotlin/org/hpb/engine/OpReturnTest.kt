package org.hpb.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpReturnTest {
    private val id = ByteArray(32) { it.toByte() }
    private val hash = ByteArray(32) { (it + 100).toByte() }

    /** Data classes with ByteArray fields compare by reference, so round-trip
     *  equality is asserted on the canonical encoding. */
    private fun assertRoundTrips(record: OpReturn.Record) {
        val encoded = OpReturn.encode(record)
        assertTrue(encoded.size <= 80)
        val decoded = OpReturn.decode(encoded)!!
        assertEquals(encoded.hex(), OpReturn.encode(decoded).hex())
        assertEquals(record::class, decoded::class)
    }

    @Test
    fun allTypesRoundTrip() {
        assertRoundTrips(OpReturn.Setup(id, hash, 5, 3))
        assertRoundTrips(OpReturn.Payout(id, hash, forceComplete = true, finalized = false))
        assertRoundTrips(OpReturn.Payout(id, hash, forceComplete = false, finalized = true))
        assertRoundTrips(OpReturn.Cancel(id))
        assertRoundTrips(OpReturn.Complete(id, hash))
        assertRoundTrips(OpReturn.Withdraw)
        assertRoundTrips(OpReturn.Stake(id, 123456L))
    }

    @Test
    fun rejectsBadInputs() {
        assertFailsWith<IllegalArgumentException> { OpReturn.encode(OpReturn.Setup(id, hash, 60, 50)) }
        assertFailsWith<IllegalArgumentException> {
            OpReturn.encode(OpReturn.Setup(ByteArray(31), hash, 1, 1))
        }
        assertNull(OpReturn.decode("XXXX".toByteArray() + ByteArray(10)))
        assertNull(OpReturn.decode(ByteArray(3)))
        // malformed known types decode to Unknown, NEVER throw — a third
        // party's garbage under our magic must not wedge scanners
        val truncated = OpReturn.encode(OpReturn.Cancel(id)).dropLast(1).toByteArray()
        assertTrue(OpReturn.decode(truncated) is OpReturn.Unknown)
        val badFees = OpReturn.encode(OpReturn.Setup(id, hash, 50, 50)).also { it[it.size - 1] = 99 }
        assertTrue(OpReturn.decode(badFees) is OpReturn.Unknown)
    }

    @Test
    fun unknownTypeToleratedFuzzDoesNotCrash() {
        val unknown = "HMTB".toByteArray() + byteArrayOf(0x01, 0x7F) + ByteArray(10)
        assertTrue(OpReturn.decode(unknown) is OpReturn.Unknown)

        // chain data is adversarial: decode must never throw, full stop
        val rng = Random(1234)
        repeat(5_000) {
            OpReturn.decode(rng.nextBytes(rng.nextInt(0, 90)))
            OpReturn.decode("HMTB".toByteArray() + rng.nextBytes(rng.nextInt(0, 76)))
        }
    }

    @Test
    fun scriptPubKeyDecoding() {
        val encoded = OpReturn.encode(OpReturn.Cancel(id))
        val script = byteArrayOf(0x6a, encoded.size.toByte()) + encoded
        val decoded = OpReturn.decodeScriptPubKey(script.hex())
        assertTrue(decoded is OpReturn.Cancel && decoded.escrowId.contentEquals(id))
        assertNull(OpReturn.decodeScriptPubKey("51"))
        assertNull(OpReturn.decodeScriptPubKey(""))
    }
}
