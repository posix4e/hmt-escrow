package org.hpb.protocol

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hpb.engine.Secp
import org.hpb.engine.nostr.Events
import org.junit.jupiter.api.Test

/**
 * The access handshake: the invitation key stays secret to the worker, the
 * CVAT identity stays public so witnesses can re-check the guard, and the
 * guard resolves conflicts the same way for everyone who applies it.
 */
class CvatAccessTest {
    private val workerKey = ByteArray(32) { if (it == 31) 0x11 else 0 }
    private val launcherKey = ByteArray(32) { if (it == 31) 0x22 else 0 }
    private val stranger = ByteArray(32) { if (it == 31) 0x33 else 0 }
    private val worker = Secp.xonlyHex(workerKey)
    private val launcher = Secp.xonlyHex(launcherKey)

    @Test
    fun `the launcher reads the requested cvat identity`() {
        val request = CvatAccessRequest("claim-1", "escrow-1", "worker@example.invalid")
        val event = CvatAccessCodec.request(workerKey, launcher, request, createdAt = 100)
        assertEquals(request, CvatAccessCodec.parseRequest(event, launcherKey))
    }

    @Test
    fun `a stranger cannot read the requested identity`() {
        val request = CvatAccessRequest("claim-1", "escrow-1", "worker@example.invalid")
        val event = CvatAccessCodec.request(workerKey, launcher, request, createdAt = 100)
        assertFailsWith<Exception> { CvatAccessCodec.parseRequest(event, stranger) }
    }

    @Test
    fun `the worker recovers its invitation key`() {
        val event = CvatAccessCodec.grant(launcherKey, accessGrant(), createdAt = 200)
        assertEquals(accessGrant(), CvatAccessCodec.parseGrant(event, workerKey))
    }

    @Test
    fun `the invitation key never appears in the clear`() {
        val event = CvatAccessCodec.grant(launcherKey, accessGrant(), createdAt = 200)
        assertTrue(SECRET !in event.content, "invitation key leaked into content")
        assertTrue(event.tags.none { row -> row.any { SECRET in it } }, "invitation key leaked into tags")
    }

    @Test
    fun `any observer reads the binding without a key`() {
        val event = CvatAccessCodec.grant(launcherKey, accessGrant(), createdAt = 200)
        val binding = CvatAccessCodec.binding(event)
        assertEquals(worker, binding?.workerPubkey)
        assertEquals(CVAT_ID, binding?.cvatId)
        assertEquals("escrow-1", binding?.escrowId)
    }

    @Test
    fun `a non-grant event yields no binding`() {
        val other = Events.sign(launcherKey, ProtocolKinds.RECEIPT, emptyList(), "{}", createdAt = 1)
        assertNull(CvatAccessCodec.binding(other))
    }

    @Test
    fun `one cvat account cannot back two workers`() {
        val first = binding("w1", CVAT_ID, at = 10, id = "a")
        val second = binding("w2", CVAT_ID, at = 20, id = "b")
        val resolved = CvatIdentity.resolve(listOf(second, first))
        assertContentEquals(listOf(first), resolved.admitted, "the earliest binding should win")
        assertContentEquals(listOf(second), resolved.rejected)
    }

    @Test
    fun `one worker cannot hold two cvat accounts`() {
        val first = binding("w1", 1, at = 10, id = "a")
        val second = binding("w1", 2, at = 20, id = "b")
        assertContentEquals(listOf(first), CvatIdentity.resolve(listOf(first, second)).admitted)
    }

    /** A rejected binding must not burn a CVAT account nobody ended up holding. */
    @Test
    fun `a rejected binding does not consume its cvat account`() {
        val first = binding("w1", 1, at = 10, id = "a")
        val duplicateWorker = binding("w1", 2, at = 20, id = "b")
        val later = binding("w2", 2, at = 30, id = "c")
        val resolved = CvatIdentity.resolve(listOf(first, duplicateWorker, later))
        assertContentEquals(listOf(first, later), resolved.admitted)
        assertContentEquals(listOf(duplicateWorker), resolved.rejected)
    }

    /** Every observer must reach the same map regardless of arrival order. */
    @Test
    fun `resolution does not depend on arrival order`() {
        val rows = listOf(
            binding("w1", 1, at = 10, id = "a"),
            binding("w2", 1, at = 10, id = "b"),
            binding("w3", 3, at = 5, id = "c"),
        )
        assertEquals(CvatIdentity.admitted(rows), CvatIdentity.admitted(rows.reversed()))
        assertEquals(mapOf("w3" to 3L, "w1" to 1L), CvatIdentity.admitted(rows))
    }

    private fun accessGrant() = CvatAccessGrant(
        grantEventId = "grant-1",
        escrowId = "escrow-1",
        workerPubkey = worker,
        cvatId = CVAT_ID,
        baseUrl = "http://cvat.invalid",
        orgSlug = "hpb",
        invitationKey = SECRET,
    )

    private fun binding(who: String, cvatId: Long, at: Long, id: String) =
        CvatBinding("grant-$id", "escrow-1", who, cvatId, at, id)

    private companion object {
        const val SECRET = "an-invitation-key-that-must-stay-secret"
        const val CVAT_ID = 42L
    }
}
