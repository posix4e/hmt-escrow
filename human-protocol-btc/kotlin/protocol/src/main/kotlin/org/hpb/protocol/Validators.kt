package org.hpb.protocol

import org.hpb.engine.hex
import org.hpb.engine.sha256

/**
 * Mechanical validation: pure functions over the revealed submission set.
 * Any observer recomputes both acceptance and the payout list — validation
 * needs no judge, only reveal.
 */
object Validators {
    fun normalize(answer: String): String = answer.trim().lowercase()

    /** The commitment format for groundtruth sets: sha256("key:normalized"). */
    fun groundtruthHash(taskKey: String, answer: String): String =
        sha256("$taskKey:${normalize(answer)}".toByteArray()).hex()

    /** (taskKey, worker) -> answer, from all revealed submissions. */
    data class Submitted(val taskKey: String, val worker: String, val answer: String)

    private val GRANTED = setOf(
        AssignmentStatus.ACTIVE, AssignmentStatus.SUBMITTED, AssignmentStatus.VALIDATED,
    )

    /**
     * Grant-scoping, applied by the launcher when collecting AND re-checked
     * by every witness against the reveal: a row counts only when its worker
     * holds a granted assignment covering the task, and only once per
     * (worker, task) — first occurrence wins. Without this, one worker
     * repeating an answer earns one reward per repetition.
     */
    fun scoped(rows: List<Submitted>, assignments: List<AssignmentState>): List<Submitted> {
        val granted = assignments.filter { it.status in GRANTED }
            .associate { it.worker to it.taskKeys.toSet() }
        val seen = HashSet<Pair<String, String>>()
        return rows.filter { row ->
            row.taskKey in granted[row.worker].orEmpty() && seen.add(row.worker to row.taskKey)
        }
    }

    fun validate(policy: ValidationPolicy, submissions: List<Submitted>): List<ResultRow> =
        when (policy.type) {
            ValidationType.GROUNDTRUTH -> submissions.map {
                ResultRow(
                    it.taskKey, it.worker, it.answer,
                    accepted = groundtruthHash(it.taskKey, it.answer) in policy.groundtruthHashes,
                )
            }
            ValidationType.AGREEMENT -> agreement(policy, submissions)
        }

    /**
     * Inter-worker consensus: per task, the modal normalized answer wins when
     * it reaches ceil(n * threshold) of that task's submissions; workers whose
     * answers match the winning cluster are accepted. Deterministic tie-break:
     * lexicographically smallest modal answer.
     */
    private fun agreement(
        policy: ValidationPolicy,
        submissions: List<Submitted>,
    ): List<ResultRow> {
        val winners = submissions.groupBy { it.taskKey }.mapValues { (_, subs) ->
            winningAnswer(policy, subs)
        }
        return submissions.map {
            ResultRow(
                it.taskKey, it.worker, it.answer,
                accepted = winners[it.taskKey] != null &&
                    normalize(it.answer) == winners[it.taskKey],
            )
        }
    }

    private fun winningAnswer(policy: ValidationPolicy, subs: List<Submitted>): String? {
        val counts = subs.groupingBy { normalize(it.answer) }.eachCount()
        val best = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first()
        val needed = Math.ceil(subs.size * policy.agreementThreshold).toInt().coerceAtLeast(1)
        return if (best.value >= needed) best.key else null
    }

    /**
     * The deterministic payout list: reward per accepted answer, aggregated
     * per worker, ordered by worker pubkey — everyone derives the same list.
     */
    fun payouts(
        rewardPerTaskSats: Long,
        rows: List<ResultRow>,
        payoutAddressOf: (String) -> String,
    ): List<PayoutLine> = rows
        .filter { it.accepted }
        .groupingBy { it.worker }
        .eachCount()
        .toSortedMap()
        .map { (worker, accepted) ->
            PayoutLine(worker, payoutAddressOf(worker), rewardPerTaskSats * accepted)
        }
}
