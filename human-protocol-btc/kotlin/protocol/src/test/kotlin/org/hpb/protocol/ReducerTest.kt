package org.hpb.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import org.hpb.engine.Secp
import org.hpb.engine.nostr.NostrEvent

class ReducerTest {
    private val launcherKey = ByteArray(32).also { it[31] = 1 }
    private val worker1Key = ByteArray(32).also { it[31] = 2 }
    private val worker2Key = ByteArray(32).also { it[31] = 3 }
    private val intruderKey = ByteArray(32).also { it[31] = 4 }
    private val launcher = Secp.xonlyHex(launcherKey)

    private val offer = Offers.toEvent(
        launcherKey,
        JobOffer(
            escrowId = "00".repeat(32),
            escrowAddress = "bcrt1qtest",
            jobType = "text_answer",
            rewardPerTaskSats = 1000,
            tasks = listOf(Task("t1", "q1"), Task("t2", "q2")),
            validation = ValidationPolicy(ValidationType.AGREEMENT, assignmentsPerTask = 2),
            kyc = KycPolicy(required = false),
            expiresAt = 10_000,
        ),
        createdAt = 100,
    )

    private fun claim(key: ByteArray, at: Long): NostrEvent = Assignments.claim(
        key, launcher, Claim(offer.id, "00".repeat(32), "bcrt1qworker", emptyList()), at,
    )

    private fun statusOf(events: List<NostrEvent>, worker: ByteArray, now: Long = 500): AssignmentStatus =
        Reducer.reduce(offer, events, now)
            .single { it.worker == Secp.xonlyHex(worker) }.status

    @Test
    fun grantAuthorityIsTheOfferAuthorOnly() {
        val claim1 = claim(worker1Key, 101)
        val forgedGrant = Assignments.grant(
            intruderKey, Secp.xonlyHex(worker1Key),
            Grant(claim1.id, "00".repeat(32), true, listOf("t1"), 9999), 102,
        )
        assertEquals(AssignmentStatus.CLAIMED, statusOf(listOf(claim1, forgedGrant), worker1Key))

        val realGrant = Assignments.grant(
            launcherKey, Secp.xonlyHex(worker1Key),
            Grant(claim1.id, "00".repeat(32), true, listOf("t1"), 9999), 103,
        )
        assertEquals(AssignmentStatus.ACTIVE, statusOf(listOf(claim1, realGrant), worker1Key))
    }

    @Test
    fun fullLifecycleAndOrderingDeterminism() {
        val claim1 = claim(worker1Key, 101)
        val claim2 = claim(worker2Key, 101)
        val grant1 = Assignments.grant(
            launcherKey, Secp.xonlyHex(worker1Key),
            Grant(claim1.id, "00".repeat(32), true, listOf("t1"), 9999), 102,
        )
        val reject2 = Assignments.grant(
            launcherKey, Secp.xonlyHex(worker2Key),
            Grant(claim2.id, "00".repeat(32), false, emptyList(), 0, reason = "no capacity"), 102,
        )
        val submission = Assignments.submission(
            worker1Key, launcher,
            Submission(grant1.id, "00".repeat(32), listOf(Answer("t1", "cat"))), 103,
        )
        val reveal = Validations.toEvent(
            launcherKey,
            EscrowResults(
                "00".repeat(32),
                listOf(ResultRow("t1", Secp.xonlyHex(worker1Key), "cat", true)),
            ),
            104,
        )

        val events = listOf(claim1, claim2, grant1, reject2, submission, reveal)
        // shuffled input must reduce identically (created_at + id ordering)
        assertEquals(
            Reducer.reduce(offer, events, 500),
            Reducer.reduce(offer, events.reversed(), 500),
        )
        assertEquals(AssignmentStatus.VALIDATED, statusOf(events, worker1Key))
        assertEquals(AssignmentStatus.REJECTED, statusOf(events, worker2Key))
    }

    @Test
    fun causallyChainedEventsSurviveTimestampInversion() {
        // Causally chained events routinely share a second (and clocks skew),
        // and a bare (created_at, id) sort tie-breaks on random ids — which
        // would silently drop a grant sorting before its claim or a reveal
        // sorting before its submission. Phase ordering makes both immune;
        // here every referencing event is timestamped BEFORE its antecedent.
        val claim1 = claim(worker1Key, 104)
        val grant1 = Assignments.grant(
            launcherKey, Secp.xonlyHex(worker1Key),
            Grant(claim1.id, "00".repeat(32), true, listOf("t1"), 9999), 103,
        )
        val submission = Assignments.submission(
            worker1Key, launcher,
            Submission(grant1.id, "00".repeat(32), listOf(Answer("t1", "cat"))), 102,
        )
        val reveal = Validations.toEvent(
            launcherKey,
            EscrowResults(
                "00".repeat(32),
                listOf(ResultRow("t1", Secp.xonlyHex(worker1Key), "cat", true)),
            ),
            101,
        )
        assertEquals(
            AssignmentStatus.VALIDATED,
            statusOf(listOf(reveal, submission, grant1, claim1), worker1Key),
        )
    }

    @Test
    fun resignAndExpiry() {
        val claim1 = claim(worker1Key, 101)
        val grant1 = Assignments.grant(
            launcherKey, Secp.xonlyHex(worker1Key),
            Grant(claim1.id, "00".repeat(32), true, listOf("t1"), expiresAt = 200), 102,
        )
        val resign = Assignments.resign(worker1Key, launcher, grant1.id, "00".repeat(32), 103)
        assertEquals(AssignmentStatus.RESIGNED, statusOf(listOf(claim1, grant1, resign), worker1Key))

        // without the resign, the grant expires at read time
        assertEquals(
            AssignmentStatus.EXPIRED,
            statusOf(listOf(claim1, grant1), worker1Key, now = 300),
        )
        assertEquals(
            AssignmentStatus.ACTIVE,
            statusOf(listOf(claim1, grant1), worker1Key, now = 150),
        )
    }

    @Test
    fun submissionRoundTripsThroughNip44() {
        val submission = Assignments.submission(
            worker1Key, launcher,
            Submission("grant-id", "00".repeat(32), listOf(Answer("t1", "hello world"))), 103,
        )
        val answers = Assignments.decryptSubmission(submission, launcherKey)
        assertEquals(listOf(Answer("t1", "hello world")), answers)
    }
}
