package org.hpb.cvat

import org.hpb.protocol.ExternalWork
import org.hpb.protocol.Validators

/**
 * Reads finished work back out of CVAT so the protocol can judge it.
 *
 * The launcher assigns and the worker annotates, but neither may decide what
 * the annotations *were*: the launcher administers CVAT and could edit them,
 * and the worker is the party being paid. So this pulls them independently,
 * canonicalises them, and hands them to [ExternalWork.substitute], which keeps
 * only what matches the hash the worker signed at submission time.
 *
 * Run more than one. [agree] is how two of them are held to the same answer.
 */
class CvatRecordingRole(private val client: CvatClient, private val labelsById: Map<Long, String>) {

    /** The canonical annotations for one CVAT job, as this recorder saw them. */
    fun pull(jobId: Long): String {
        val tags = client.jobTags(jobId).map { it.frame to (labelsById[it.labelId] ?: UNKNOWN_LABEL) }
        return ExternalWork.canonicalAnnotations(tags)
    }

    /**
     * Pull every task a worker was granted, keyed the way [ExternalWork.substitute]
     * expects. Failures are omitted rather than guessed: a job that cannot be
     * read is unverifiable, and unverifiable work is not paid.
     */
    fun pullAll(assignments: List<CvatAssignment>): Map<Pair<String, String>, String> =
        assignments.mapNotNull { assignment ->
            runCatching { pull(assignment.cvatJobId) }
                .getOrNull()
                ?.let { (assignment.worker to assignment.taskKey) to it }
        }.toMap()

    companion object {
        const val UNKNOWN_LABEL = "unknown"

        /**
         * What two recorders agree on, and nothing more.
         *
         * Cross-checking defends against a broken or dishonest recorder. It
         * does *not* defend against whoever operates CVAT editing annotations
         * before every recorder reads them — that is what the worker's
         * commitment is for. Disagreement is reported by omission, never
         * resolved by preferring one side.
         */
        fun agree(
            first: Map<Pair<String, String>, String>,
            second: Map<Pair<String, String>, String>,
        ): Map<Pair<String, String>, String> =
            first.filter { (key, value) -> second[key] == value }

        /** The rows validation should run over, once the answers are pulled. */
        fun resolve(
            submitted: List<Validators.Submitted>,
            pulled: Map<Pair<String, String>, String>,
        ): List<Validators.Submitted> = ExternalWork.substitute(submitted, pulled)
    }
}

/** One worker's granted assignment, tying a protocol task to a CVAT job. */
data class CvatAssignment(val worker: String, val taskKey: String, val cvatJobId: Long)
