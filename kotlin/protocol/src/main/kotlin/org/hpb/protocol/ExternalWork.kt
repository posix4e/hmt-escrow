package org.hpb.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.hex
import org.hpb.engine.sha256
import org.hpb.protocol.Pj.a
import org.hpb.protocol.Pj.l
import org.hpb.protocol.Pj.o
import org.hpb.protocol.Pj.s

/** Where the work actually lives, when it is not carried in the offer. */
data class CvatWorkSource(
    val baseUrl: String,
    val org: String,
    val taskId: Long,
    val jobId: Long,
    val labels: List<String>,
) {
    /** What the worker opens in a browser. */
    val url: String get() = "${baseUrl.trimEnd('/')}/tasks/$taskId/jobs/$jobId"
}

/**
 * What a worker asserts instead of an answer: that they finished an assignment
 * in the external tool, and what their work hashed to when they read it back.
 */
data class CvatCompletion(
    val cvatJobId: Long,
    val cvatUserId: Long,
    val annotationsSha256: String,
)

/**
 * The phase-0 encoding for work that lives in another tool.
 *
 * `Task.question` and `Answer.answer` are free-form strings, and the on-chain
 * manifest already commits `key` and `question` — so a work reference costs no
 * schema change, no vector regeneration and no Swift lockstep. `Claim` and
 * `Grant` contents are closed shapes and deliberately untouched; the CVAT
 * access handshake rides beside them instead (see [CvatAccessCodec]).
 *
 * Parse it here and nowhere else. The mirror of this file is
 * `ios/HpbCore/Sources/HpbCore/ExternalWork.swift`, and the two must agree
 * byte for byte on [canonicalAnnotations], because the worker hashes it on a
 * phone and the recording role re-hashes it on a server.
 */
object ExternalWork {
    const val TOOL_CVAT = "cvat"

    /**
     * A `text` field is always emitted: clients that predate this encoding
     * fall back to showing the raw question string when neither `text` nor
     * `image` is present, which would put JSON in front of a worker.
     */
    fun question(text: String, work: CvatWorkSource): String = Pj.obj(
        "text" to Pj.str(text),
        "work" to Pj.obj(
            "tool" to Pj.str(TOOL_CVAT),
            "base_url" to Pj.str(work.baseUrl),
            "org" to Pj.str(work.org),
            "task_id" to Pj.num(work.taskId),
            "job_id" to Pj.num(work.jobId),
            "labels" to Pj.arr(work.labels.map(Pj::str)),
            "url" to Pj.str(work.url),
        ),
    ).toString()

    /** Null for an ordinary inline task, so old offers keep working. */
    fun workSource(question: String): CvatWorkSource? {
        val work = runCatching { Pj.parse(question).o("work") }.getOrNull() ?: return null
        if (runCatching { work.s("tool") }.getOrNull() != TOOL_CVAT) return null
        return runCatching {
            CvatWorkSource(
                baseUrl = work.s("base_url"),
                org = work.s("org"),
                taskId = work.l("task_id"),
                jobId = work.l("job_id"),
                labels = work.a("labels").map { it.jsonPrimitive.content },
            )
        }.getOrNull()
    }

    fun answer(completion: CvatCompletion): String = Pj.obj(
        "completed" to Pj.str("true"),
        "cvat_job_id" to Pj.num(completion.cvatJobId),
        "cvat_user_id" to Pj.num(completion.cvatUserId),
        "annotations_sha256" to Pj.str(completion.annotationsSha256),
    ).toString()

    fun completion(answer: String): CvatCompletion? {
        val parsed: JsonObject = runCatching { Pj.parse(answer) }.getOrNull() ?: return null
        return runCatching {
            CvatCompletion(
                cvatJobId = parsed.l("cvat_job_id"),
                cvatUserId = parsed.l("cvat_user_id"),
                annotationsSha256 = parsed.s("annotations_sha256"),
            )
        }.getOrNull()
    }

    /**
     * The bytes both sides hash. Frames ascending, then label; labels
     * normalized exactly as [Validators.normalize] does, so a canonical
     * annotation set and a validated answer cannot disagree over whitespace
     * or case.
     */
    fun canonicalAnnotations(tags: List<Pair<Int, String>>): String =
        tags.map { it.first to Validators.normalize(it.second) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("\n") { "${it.first}:${it.second}" }

    fun annotationsHash(tags: List<Pair<Int, String>>): String =
        sha256(canonicalAnnotations(tags).toByteArray()).hex()

    fun hashOf(canonical: String): String = sha256(canonical.toByteArray()).hex()

    /**
     * Swap each worker's completion assertion for the annotations someone
     * actually pulled — but only where those annotations hash to what the
     * worker committed to at submission time.
     *
     * This is what lets validation stay mechanical once the answers live in
     * another system. A witness has no CVAT access and cannot fetch anything,
     * yet it can re-run this over the reveal and the submissions alone: a
     * launcher cannot invent an answer, because the bytes must hash to a
     * commitment the worker signed before the launcher saw them. A row whose
     * hash disagrees is dropped rather than guessed at — unverifiable work is
     * not paid, and neither is work someone tampered with afterwards.
     *
     * Rows that are not completion assertions pass through untouched, so
     * ordinary inline tasks are unaffected.
     */
    fun substitute(
        submitted: List<Validators.Submitted>,
        pulled: Map<Pair<String, String>, String>,
    ): List<Validators.Submitted> = submitted.mapNotNull { row ->
        val completion = completion(row.answer) ?: return@mapNotNull row
        val canonical = pulled[row.worker to row.taskKey] ?: return@mapNotNull null
        if (hashOf(canonical) != completion.annotationsSha256) return@mapNotNull null
        row.copy(answer = canonical)
    }
}
