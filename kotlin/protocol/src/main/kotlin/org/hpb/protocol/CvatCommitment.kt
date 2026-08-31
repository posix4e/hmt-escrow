package org.hpb.protocol

import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent

/** A worker's signed statement of what its own annotations hashed to. */
data class CvatCommitment(
    val escrowId: String,
    val taskKey: String,
    val worker: String,
    val annotationsSha256: String,
)

/**
 * The commitment is **public**, and that is the whole point.
 *
 * Submissions are NIP-44 encrypted to the launcher, so a witness cannot read
 * them; it takes answers from the launcher's reveal and only re-checks that the
 * payout follows mechanically. Once the answers live in CVAT that is not
 * enough — the launcher administers CVAT, so without something else it could
 * reveal whatever it liked.
 *
 * So the worker publishes the hash of its own work in the clear, signed by its
 * own key, before the launcher reveals anything. Any observer can then hold the
 * reveal to it: an answer that does not hash to what the worker committed is
 * not the worker's work, and is not paid. No CVAT access is required to check
 * this, which is what keeps validation mechanical.
 *
 * It rides beside the submission rather than inside it, because the submission
 * is a closed shape in the byte-locked vector corpus.
 */
object CvatCommitments {
    fun toEvent(privkey: ByteArray, commitment: CvatCommitment, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.CVAT_COMMITMENT,
            tags = listOf(
                listOf("x", commitment.escrowId),
                listOf("k", commitment.taskKey),
                listOf("h", commitment.annotationsSha256),
            ),
            content = Pj.obj("v" to Pj.num(ProtocolKinds.VERSION)).toString(),
            createdAt = createdAt,
        )

    fun fromEvent(event: NostrEvent): CvatCommitment? {
        if (event.kind != ProtocolKinds.CVAT_COMMITMENT) return null
        return CvatCommitment(
            escrowId = event.tagValue("x") ?: return null,
            taskKey = event.tagValue("k") ?: return null,
            worker = event.pubkey,
            annotationsSha256 = event.tagValue("h") ?: return null,
        )
    }

    /**
     * (worker, task) → hash, earliest commitment winning.
     *
     * A worker who publishes a second, different hash after seeing what others
     * did must not be able to move its own goalposts, so later ones are
     * ignored — the same first-wins rule the reducer uses, ordered the same way.
     */
    fun index(events: List<NostrEvent>): Map<Pair<String, String>, String> {
        val ordered = events.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val byKey = LinkedHashMap<Pair<String, String>, String>()
        ordered.mapNotNull(::fromEvent).forEach { commitment ->
            byKey.putIfAbsent(commitment.worker to commitment.taskKey, commitment.annotationsSha256)
        }
        return byKey
    }
}
