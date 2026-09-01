package org.hpb.protocol

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent
import org.hpb.protocol.Pj.a

/**
 * What a worker's control plane says it can handle.
 *
 * This is *not* "which tools can I execute" — execution may be routed to a
 * desktop or an agent. It is "which tools can I **verify results for**", which
 * is the thing that actually decides whether a worker can complete a job: the
 * control plane must be able to read the work back out of the tool and commit
 * to its hash. A worker signed out of a tool cannot do that, and a launcher
 * granting it work would strand the job.
 *
 * Addressable, so the latest declaration by an author wins — a worker that signs
 * out republishes a narrower list rather than deleting anything.
 *
 * It rides beside the claim rather than inside it: `Claim` is a closed shape in
 * the byte-locked vector corpus, and the launcher can look this up by author
 * pubkey anyway. Every witness can re-derive the same answer.
 */
data class WorkerTools(val worker: String, val tools: List<String>)

object WorkerToolsCodec {
    const val D_TAG = "tools"

    fun toEvent(privkey: ByteArray, tools: List<String>, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.WORKER_TOOLS,
            tags = listOf(listOf("d", D_TAG)),
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "tools" to Pj.arr(tools.sorted().map(Pj::str)),
            ).toString(),
            createdAt = createdAt,
        )

    fun fromEvent(event: NostrEvent): WorkerTools? {
        if (event.kind != ProtocolKinds.WORKER_TOOLS) return null
        val tools = runCatching {
            Pj.parse(event.content).a("tools").map { it.jsonPrimitive.content }
        }.getOrNull() ?: return null
        return WorkerTools(event.pubkey, tools)
    }

    /** The newest declaration per worker, since the kind is addressable. */
    fun latest(events: List<NostrEvent>): Map<String, List<String>> =
        events.sortedWith(compareBy({ it.createdAt }, { it.id }))
            .mapNotNull(::fromEvent)
            .associate { it.worker to it.tools }
}
