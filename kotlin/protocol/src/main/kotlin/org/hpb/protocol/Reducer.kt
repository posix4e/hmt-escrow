package org.hpb.protocol

import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent

enum class AssignmentStatus { CLAIMED, ACTIVE, REJECTED, RESIGNED, SUBMITTED, VALIDATED, EXPIRED }

data class AssignmentState(
    val claimEventId: String,
    val worker: String,
    val status: AssignmentStatus,
    val grantEventId: String? = null,
    val taskKeys: List<String> = emptyList(),
    val expiresAt: Long? = null,
    val payoutAddress: String,
)

/**
 * The deterministic assignment reducer — every party derives identical state
 * from the event set alone. Ordering: causal phase first (claims, then the
 * grants that reference them, then the worker moves a grant enables, then
 * reveals), created_at ascending + event-id tie-break within a phase. Phase
 * ordering matters: causally chained events routinely share a second, and a
 * bare (created_at, id) sort tie-breaks on effectively random ids — a grant
 * sorting before its claim (or a reveal before its submission) would be
 * silently dropped. Authority table: only the offer author's grants
 * allocate; only the worker's own resign/submission move its assignment;
 * validations come from the offer author. Wrong-party events are IGNORED,
 * not errors.
 */
object Reducer {
    fun reduce(offerEvent: NostrEvent, events: List<NostrEvent>, now: Long): List<AssignmentState> {
        val launcher = offerEvent.pubkey
        val ordered = events.filter(Events::verify)
            .sortedWith(compareBy({ PHASE[it.kind] ?: 0 }, { it.createdAt }, { it.id }))
        val states = LinkedHashMap<String, AssignmentState>()
        for (event in ordered) {
            applyEvent(states, offerEvent, launcher, event)
        }
        return states.values.map { expire(it, now) }
    }

    private val PHASE = mapOf(
        ProtocolKinds.CLAIM to 0,
        ProtocolKinds.GRANT to 1,
        ProtocolKinds.RESIGN to 2,
        ProtocolKinds.SUBMISSION to 2,
        ProtocolKinds.VALIDATION to 3,
    )

    private val LAUNCHER_KINDS = setOf(ProtocolKinds.GRANT, ProtocolKinds.VALIDATION)

    private val HANDLERS: Map<Int, (LinkedHashMap<String, AssignmentState>, NostrEvent, NostrEvent) -> Unit> =
        mapOf(
            ProtocolKinds.CLAIM to { states, offer, event -> applyClaim(states, offer, event) },
            ProtocolKinds.GRANT to { states, _, event -> applyGrant(states, event) },
            ProtocolKinds.RESIGN to { states, _, event -> applyResign(states, event) },
            ProtocolKinds.SUBMISSION to { states, _, event -> applySubmission(states, event) },
            ProtocolKinds.VALIDATION to { states, _, event -> applyValidation(states, event) },
        )

    private fun applyEvent(
        states: LinkedHashMap<String, AssignmentState>,
        offerEvent: NostrEvent,
        launcher: String,
        event: NostrEvent,
    ) {
        if (event.kind in LAUNCHER_KINDS && event.pubkey != launcher) return
        HANDLERS[event.kind]?.invoke(states, offerEvent, event)
    }

    private fun applyClaim(
        states: LinkedHashMap<String, AssignmentState>,
        offerEvent: NostrEvent,
        event: NostrEvent,
    ) {
        val claim = runCatching { Assignments.parseClaim(event) }.getOrNull() ?: return
        if (claim.offerEventId != offerEvent.id || states.containsKey(event.id)) return
        states[event.id] = AssignmentState(
            claimEventId = event.id,
            worker = event.pubkey,
            status = AssignmentStatus.CLAIMED,
            payoutAddress = claim.payoutAddress,
        )
    }

    private fun applyGrant(states: LinkedHashMap<String, AssignmentState>, event: NostrEvent) {
        val grant = runCatching { Assignments.parseGrant(event) }.getOrNull() ?: return
        val state = states[grant.claimEventId] ?: return
        if (state.status != AssignmentStatus.CLAIMED) return
        states[grant.claimEventId] = state.copy(
            status = if (grant.granted) AssignmentStatus.ACTIVE else AssignmentStatus.REJECTED,
            grantEventId = event.id,
            taskKeys = grant.taskKeys,
            expiresAt = grant.expiresAt,
        )
    }

    private fun applyResign(states: LinkedHashMap<String, AssignmentState>, event: NostrEvent) {
        val state = states.values.firstOrNull { it.grantEventId == event.tagValue("e") } ?: return
        if (event.pubkey != state.worker || state.status != AssignmentStatus.ACTIVE) return
        states[state.claimEventId] = state.copy(status = AssignmentStatus.RESIGNED)
    }

    private fun applySubmission(states: LinkedHashMap<String, AssignmentState>, event: NostrEvent) {
        val state = states.values.firstOrNull { it.grantEventId == event.tagValue("e") } ?: return
        if (event.pubkey != state.worker || state.status != AssignmentStatus.ACTIVE) return
        states[state.claimEventId] = state.copy(status = AssignmentStatus.SUBMITTED)
    }

    /** The escrow-results reveal marks every submitted assignment validated. */
    private fun applyValidation(states: LinkedHashMap<String, AssignmentState>, event: NostrEvent) {
        val results = runCatching { Validations.fromEvent(event) }.getOrNull() ?: return
        val workersInResults = results.rows.map { it.worker }.toSet()
        states.replaceAll { _, state ->
            if (state.status == AssignmentStatus.SUBMITTED && state.worker in workersInResults) {
                state.copy(status = AssignmentStatus.VALIDATED)
            } else {
                state
            }
        }
    }

    /** Wall-clock expiry, evaluated lazily at read time. */
    private fun expire(state: AssignmentState, now: Long): AssignmentState =
        if (state.status == AssignmentStatus.ACTIVE && state.expiresAt != null &&
            now > state.expiresAt
        ) {
            state.copy(status = AssignmentStatus.EXPIRED)
        } else {
            state
        }
}
