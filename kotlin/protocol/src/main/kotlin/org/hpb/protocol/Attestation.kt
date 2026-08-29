package org.hpb.protocol

import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent
import org.hpb.protocol.Pj.l
import org.hpb.protocol.Pj.s

/**
 * Portable KYC/qualification attestations. KYC is OPTIONAL at the job level:
 * offers declare accepted attesters; workers without badges still see and
 * take open jobs. Attesters compete — no single gatekeeper.
 */
data class Attestation(
    val schema: String,
    val subject: String,
    val status: String, // "valid" | "revoked"
    val issuedAt: Long,
    val validUntil: Long,
)

object Attestations {
    fun toEvent(attesterPrivkey: ByteArray, a: Attestation, createdAt: Long): NostrEvent =
        Events.sign(
            attesterPrivkey, ProtocolKinds.ATTESTATION,
            tags = listOf(
                listOf("d", "${a.schema}:${a.subject}"),
                listOf("p", a.subject),
                listOf("t", a.schema),
            ),
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "schema" to Pj.str(a.schema),
                "subject" to Pj.str(a.subject),
                "status" to Pj.str(a.status),
                "issued_at" to Pj.num(a.issuedAt),
                "valid_until" to Pj.num(a.validUntil),
            ).toString(),
            createdAt = createdAt,
        )

    fun fromEvent(event: NostrEvent): Attestation {
        require(event.kind == ProtocolKinds.ATTESTATION) { "not an attestation" }
        val content = Pj.parse(event.content)
        return Attestation(
            schema = content.s("schema"),
            subject = content.s("subject"),
            status = content.s("status"),
            issuedAt = content.l("issued_at"),
            validUntil = content.l("valid_until"),
        )
    }

    /**
     * Does this event satisfy an offer's KYC policy for the worker, now?
     * Verifies: event signature, accepted attester, subject binding,
     * valid status, and expiry. Fails closed on any gap.
     */
    fun satisfies(event: NostrEvent, policy: KycPolicy, worker: String, now: Long): Boolean {
        if (!policy.required) return true
        if (!isAcceptedAttester(event, policy)) return false
        val attestation = runCatching { fromEvent(event) }.getOrNull() ?: return false
        return attestation.subject == worker &&
            attestation.status == "valid" &&
            now < attestation.validUntil
    }

    private fun isAcceptedAttester(event: NostrEvent, policy: KycPolicy): Boolean =
        Events.verify(event) &&
            event.kind == ProtocolKinds.ATTESTATION &&
            event.pubkey in policy.attesters
}
