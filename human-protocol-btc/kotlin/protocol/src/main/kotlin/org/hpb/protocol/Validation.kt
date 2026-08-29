package org.hpb.protocol

import kotlinx.serialization.json.jsonObject
import org.hpb.engine.hex
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.sha256
import org.hpb.protocol.Pj.a
import org.hpb.protocol.Pj.b
import org.hpb.protocol.Pj.s

/** One revealed, validated answer in the final results set. */
data class ResultRow(
    val taskKey: String,
    val worker: String,
    val answer: String,
    val accepted: Boolean,
)

/**
 * The launcher's reveal: the full validated submission set, INLINE. Anyone
 * recomputes validation and payout correctness from this event; its sha256
 * is the results hash committed in the on-chain PAYOUT record. Non-reveal is
 * a provable reputational failure (workers hold their signed submissions).
 */
data class EscrowResults(val escrowId: String, val rows: List<ResultRow>) {
    fun resultsJson(): String = Pj.obj(
        "v" to Pj.num(ProtocolKinds.VERSION),
        "rows" to Pj.arr(
            rows.map {
                Pj.obj(
                    "task_key" to Pj.str(it.taskKey),
                    "worker" to Pj.str(it.worker),
                    "answer" to Pj.str(it.answer),
                    "accepted" to Pj.bool(it.accepted),
                )
            },
        ),
    ).toString()

    fun resultsHash(): ByteArray = sha256(resultsJson().toByteArray())
}

object Validations {
    fun toEvent(privkey: ByteArray, results: EscrowResults, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.VALIDATION,
            tags = listOf(listOf("x", results.escrowId)),
            content = results.resultsJson(),
            createdAt = createdAt,
        )

    fun fromEvent(event: NostrEvent): EscrowResults {
        require(event.kind == ProtocolKinds.VALIDATION) { "not a validation" }
        val content = Pj.parse(event.content)
        return EscrowResults(
            escrowId = requireNotNull(event.tagValue("x")),
            rows = content.a("rows").map {
                val row = it.jsonObject
                ResultRow(row.s("task_key"), row.s("worker"), row.s("answer"), row.b("accepted"))
            },
        )
    }

    fun resultsHashHex(results: EscrowResults): String = results.resultsHash().hex()
}
