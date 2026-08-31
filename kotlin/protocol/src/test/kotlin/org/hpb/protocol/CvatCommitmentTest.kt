package org.hpb.protocol

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hpb.engine.Secp
import org.hpb.engine.nostr.Events
import org.junit.jupiter.api.Test

/**
 * The public commitment is what lets a witness judge work it cannot see.
 * Submissions are encrypted to the launcher, so without this the launcher —
 * which also administers CVAT — could reveal anything it liked.
 */
class CvatCommitmentTest {
    private val workerKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val worker = Secp.xonlyHex(workerKey)
    private val canonical = "0:circle\n1:square"
    private val hash = ExternalWork.hashOf(canonical)

    @Test
    fun `a commitment round-trips and is readable by anyone`() {
        val event = CvatCommitments.toEvent(workerKey, commitment(), createdAt = 100)
        assertEquals(commitment(), CvatCommitments.fromEvent(event))
        assertTrue(hash in event.tags.flatten(), "the hash must be public, not in content")
    }

    @Test
    fun `a non-commitment event yields nothing`() {
        val other = Events.sign(workerKey, ProtocolKinds.RECEIPT, emptyList(), "{}", createdAt = 1)
        assertNull(CvatCommitments.fromEvent(other))
    }

    /** A worker must not be able to move its own goalposts after the fact. */
    @Test
    fun `the earliest commitment wins`() {
        val first = CvatCommitments.toEvent(workerKey, commitment(), createdAt = 100)
        val later = CvatCommitments.toEvent(workerKey, commitment("beef"), createdAt = 200)
        assertEquals(
            mapOf((worker to "task-1") to hash),
            CvatCommitments.index(listOf(later, first)),
        )
    }

    @Test
    fun `a reveal matching every commitment is accepted`() {
        val rows = listOf(ResultRow("task-1", worker, canonical, accepted = true))
        assertTrue(ExternalWork.revealMatchesCommitments(rows, mapOf((worker to "task-1") to hash)))
    }

    /** The launcher administers CVAT; this is the check that constrains it. */
    @Test
    fun `a reveal the worker never committed to is refused`() {
        val rows = listOf(ResultRow("task-1", worker, "0:square\n1:square", accepted = true))
        assertFalse(ExternalWork.revealMatchesCommitments(rows, mapOf((worker to "task-1") to hash)))
    }

    /** Inline tasks have no commitment and must stay unaffected. */
    @Test
    fun `rows with no commitment pass through`() {
        val rows = listOf(ResultRow("task-1", worker, "cat", accepted = true))
        assertTrue(ExternalWork.revealMatchesCommitments(rows, emptyMap()))
    }

    private fun commitment(digest: String = hash) =
        CvatCommitment("escrow-1", "task-1", worker, digest)
}
