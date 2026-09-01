package org.hpb.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.hex
import org.hpb.engine.sha256
import org.hpb.protocol.Pj.l
import org.hpb.protocol.Pj.o
import org.hpb.protocol.Pj.s
import org.hpb.protocol.Pj.sOrNull

/** Which surface the work expects, and therefore where a client should route it. */
enum class WorkSurface {
    DESKTOP, MOBILE, ANY;

    companion object {
        fun parse(raw: String?): WorkSurface =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ANY
    }
}

/**
 * Where the work actually lives, when it is not carried in the offer.
 *
 * Deliberately thin: [tool] and [url] are all a client needs to route and open
 * it, so a client that has never heard of a tool still shows a working link
 * rather than nothing. Everything a tool needs beyond that lives in [params],
 * opaque to everyone except that tool's adapter.
 */
data class WorkSource(
    val tool: String,
    val url: String,
    val surface: WorkSurface = WorkSurface.ANY,
    val result: String = RESULT_TAGS,
    val params: Map<String, String> = emptyMap(),
) {
    companion object {
        const val RESULT_TAGS = "tags"
    }
}

/**
 * What a worker asserts instead of an answer: that they finished an assignment
 * in the external tool, and what their work hashed to when they read it back.
 */
data class WorkCompletion(val ref: String, val resultSha256: String)

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
    fun question(text: String, work: WorkSource): String = Pj.obj(
        "text" to Pj.str(text),
        "work" to Pj.obj(
            "tool" to Pj.str(work.tool),
            "url" to Pj.str(work.url),
            "surface" to Pj.str(work.surface.name.lowercase()),
            "result" to Pj.str(work.result),
            // Sorted so the manifest hash does not depend on map iteration order.
            "params" to paramsJson(work.params),
        ),
    ).toString()

    /** Sorted, so the manifest hash never depends on map iteration order. */
    private fun paramsJson(params: Map<String, String>): JsonObject =
        JsonObject(params.toSortedMap().mapValues { Pj.str(it.value) })

    /**
     * Null for an ordinary inline task, so old offers keep working.
     *
     * Any tool is accepted, not just CVAT: a client that cannot work a tool
     * should say so, not pretend the job does not exist.
     */
    fun workSource(question: String): WorkSource? {
        val work = runCatching { Pj.parse(question).o("work") }.getOrNull() ?: return null
        return runCatching {
            WorkSource(
                tool = work.s("tool"),
                url = work.s("url"),
                surface = WorkSurface.parse(work.sOrNull("surface")),
                result = work.sOrNull("result") ?: WorkSource.RESULT_TAGS,
                params = (work["params"] as? JsonObject).orEmpty()
                    .mapValues { it.value.jsonPrimitive.content },
            )
        }.getOrNull()
    }

    fun answer(completion: WorkCompletion): String = Pj.obj(
        "completed" to Pj.str("true"),
        "ref" to Pj.str(completion.ref),
        "result_sha256" to Pj.str(completion.resultSha256),
    ).toString()

    fun completion(answer: String): WorkCompletion? {
        val parsed: JsonObject = runCatching { Pj.parse(answer) }.getOrNull() ?: return null
        return runCatching {
            WorkCompletion(ref = parsed.s("ref"), resultSha256 = parsed.s("result_sha256"))
        }.getOrNull()
    }

    /**
     * The bytes both sides hash, for the named [form].
     *
     * The protocol's contract is only "deterministic bytes, computable
     * identically by the worker on-device and by an independent recorder" —
     * everything downstream treats the result as opaque. So each result form
     * gets its own canonicaliser, and an unrecognised one fails loudly rather
     * than hashing something arbitrary and withholding a payout later.
     *
     * This function is the one piece that must agree byte for byte with
     * `ios/HpbCore/Sources/HpbCore/ExternalWork.swift`.
     */
    fun canonical(form: String, entries: List<Pair<Int, String>>): String = when (form) {
        WorkSource.RESULT_TAGS -> canonicalAnnotations(entries)
        else -> error("unsupported result form '$form'")
    }

    /**
     * Tags: frames ascending, then label; labels normalized exactly as
     * [Validators.normalize] does, so a canonical annotation set and a
     * validated answer cannot disagree over whitespace or case.
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
     * worker publicly committed to before anyone revealed anything.
     *
     * [commitments] is the authority, not the submission: submissions are
     * encrypted to the launcher, so only a public commitment can be checked by
     * everyone. A row with no commitment, or one whose bytes disagree, is
     * dropped rather than guessed at — unverifiable work is not paid, and
     * neither is work someone edited afterwards.
     *
     * Rows that are not completion assertions pass through untouched, so
     * ordinary inline tasks are unaffected.
     */
    fun substitute(
        submitted: List<Validators.Submitted>,
        pulled: Map<Pair<String, String>, String>,
        commitments: Map<Pair<String, String>, String>,
    ): List<Validators.Submitted> = submitted.mapNotNull { row ->
        completion(row.answer) ?: return@mapNotNull row
        val key = row.worker to row.taskKey
        val committed = commitments[key] ?: return@mapNotNull null
        val canonical = pulled[key] ?: return@mapNotNull null
        if (hashOf(canonical) != committed) return@mapNotNull null
        row.copy(answer = canonical)
    }

    /**
     * Every revealed answer for external work must hash to its worker's own
     * public commitment. This is the check a witness can run with nothing but
     * Nostr: it is what stops a launcher, who administers CVAT, from revealing
     * annotations the worker never made.
     */
    fun revealMatchesCommitments(
        rows: List<ResultRow>,
        commitments: Map<Pair<String, String>, String>,
    ): Boolean = rows.all { row ->
        val committed = commitments[row.worker to row.taskKey] ?: return@all true
        hashOf(row.answer) == committed
    }
}
