package org.hpb.roles

import org.hpb.engine.Secp
import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.Answer
import org.hpb.protocol.Assignments
import org.hpb.protocol.Attestation
import org.hpb.protocol.Attestations
import org.hpb.protocol.Claim
import org.hpb.protocol.Grant
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Submission

/**
 * A worker needs only its keypair and relays — no chain access, no backend.
 * Payout confirmation arrives as a receipt naming a txid its wallet can
 * verify through its own view.
 */
class WorkerActor(private val nostr: NostrClient, private val privkey: ByteArray) {
    val pubkey: String = Secp.xonlyHex(privkey)
    private fun now() = System.currentTimeMillis() / 1000

    fun claim(
        offerEvent: NostrEvent,
        payoutAddress: String,
        attestationEventIds: List<String> = emptyList(),
    ): NostrEvent {
        val escrowId = requireNotNull(offerEvent.tagValue("d"))
        val event = Assignments.claim(
            privkey, offerEvent.pubkey,
            Claim(offerEvent.id, escrowId, payoutAddress, attestationEventIds),
            now(),
        )
        check(nostr.publish(event)) { "claim publish failed" }
        return event
    }

    fun grantFor(claimEvent: NostrEvent): Grant? = nostr.fetch(
        NostrFilter(kinds = listOf(ProtocolKinds.GRANT), eTag = claimEvent.id),
    ).firstOrNull()?.let(Assignments::parseGrant)

    fun submit(
        claimEvent: NostrEvent,
        validator: String,
        answers: List<Answer>,
    ): NostrEvent {
        val grantEvent = nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.GRANT), eTag = claimEvent.id),
        ).first()
        val escrowId = requireNotNull(claimEvent.tagValue("x"))
        val event = Assignments.submission(
            privkey, validator, Submission(grantEvent.id, escrowId, answers), now(),
        )
        check(nostr.publish(event)) { "submission publish failed" }
        return event
    }
}

/** The mock KYC attester — same event format as a production (e.g. Veriff-backed) issuer. */
class MockAttester(private val nostr: NostrClient, private val privkey: ByteArray) {
    val pubkey: String = Secp.xonlyHex(privkey)

    fun issue(worker: String, schema: String, validUntil: Long): NostrEvent {
        val event = Attestations.toEvent(
            privkey,
            Attestation(schema, worker, "valid", System.currentTimeMillis() / 1000, validUntil),
            System.currentTimeMillis() / 1000,
        )
        check(nostr.publish(event)) { "attestation publish failed" }
        return event
    }
}
