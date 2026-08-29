package org.hpb.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import org.hpb.protocol.Validators.Submitted

class ValidatorsTest {
    private val reward = 10_000L
    private fun address(worker: String) = "addr-$worker"

    @Test
    fun scopedDropsDuplicateAndUngrantedRows() {
        val assignments = listOf(
            AssignmentState("c1", "w1", AssignmentStatus.SUBMITTED, "g1", listOf("t1"), null, "a1"),
            AssignmentState("c2", "w2", AssignmentStatus.REJECTED, "g2", listOf("t1"), null, "a2"),
        )
        val rows = listOf(
            Submitted("t1", "w1", "a"),
            Submitted("t1", "w1", "a"), // repeated (worker, task): a reward per repeat otherwise
            Submitted("t2", "w1", "a"), // task not granted to w1
            Submitted("t1", "w2", "a"), // w2's claim was rejected
            Submitted("t1", "w3", "a"), // w3 has no assignment at all
        )
        assertEquals(listOf(Submitted("t1", "w1", "a")), Validators.scoped(rows, assignments))
    }

    @Test
    fun groundtruthAcceptsMatchingNormalizedAnswers() {
        val policy = ValidationPolicy(
            type = ValidationType.GROUNDTRUTH,
            groundtruthHashes = setOf(
                Validators.groundtruthHash("t1", "Paris"),
                Validators.groundtruthHash("t2", "4"),
            ),
        )
        val rows = Validators.validate(
            policy,
            listOf(
                Submitted("t1", "w1", "  paris "),
                Submitted("t1", "w2", "London"),
                Submitted("t2", "w1", "4"),
            ),
        )
        assertEquals(listOf(true, false, true), rows.map { it.accepted })
    }

    @Test
    fun agreementMajorityClusterWins() {
        val policy = ValidationPolicy(
            type = ValidationType.AGREEMENT, assignmentsPerTask = 3, agreementThreshold = 0.5,
        )
        val rows = Validators.validate(
            policy,
            listOf(
                Submitted("t1", "w1", "cat"),
                Submitted("t1", "w2", "Cat "),
                Submitted("t1", "w3", "dog"),
                Submitted("t2", "w1", "a"),
                Submitted("t2", "w2", "b"),
            ),
        )
        // t1: "cat" wins 2/3; t2: 1/2 meets ceil(2*0.5)=1 -> tie broken lexicographically ("a")
        assertEquals(listOf(true, true, false, true, false), rows.map { it.accepted })
    }

    @Test
    fun agreementBelowThresholdAcceptsNobody() {
        val policy = ValidationPolicy(
            type = ValidationType.AGREEMENT, assignmentsPerTask = 3, agreementThreshold = 0.67,
        )
        val rows = Validators.validate(
            policy,
            listOf(
                Submitted("t1", "w1", "x"),
                Submitted("t1", "w2", "y"),
                Submitted("t1", "w3", "z"),
            ),
        )
        assertEquals(listOf(false, false, false), rows.map { it.accepted })
    }

    @Test
    fun payoutsAggregateDeterministically() {
        val rows = listOf(
            ResultRow("t1", "w2", "a", true),
            ResultRow("t2", "w2", "b", true),
            ResultRow("t1", "w1", "a", true),
            ResultRow("t3", "w1", "c", false),
        )
        val lines = Validators.payouts(reward, rows, ::address)
        assertEquals(
            listOf(
                PayoutLine("w1", "addr-w1", 10_000),
                PayoutLine("w2", "addr-w2", 20_000),
            ),
            lines,
        )
    }
}
