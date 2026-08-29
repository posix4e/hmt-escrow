package org.hpb.androidcore

import org.hpb.engine.Secp
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.Answer
import org.hpb.protocol.AssignmentState
import org.hpb.protocol.Assignments
import org.hpb.protocol.Claim
import org.hpb.protocol.JobOffer
import org.hpb.protocol.Offers
import org.hpb.protocol.PayoutLine
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Receipts
import org.hpb.protocol.Reducer
import org.hpb.protocol.Submission
import org.hpb.protocol.Validations
import org.hpb.protocol.Validators

/**
 * The worker's whole app state, derived from relays alone — no backend, no
 * chain access (receipts carry txids the wallet layer verifies itself).
 * Plain Kotlin so the Compose shell stays a thin view over this.
 */
class WorkerSession(private val relays: OkRelayClient, private val privkey: ByteArray) {
    val pubkey: String = Secp.xonlyHex(privkey)
    private fun now() = System.currentTimeMillis() / 1000

    data class JobRow(val event: NostrEvent, val offer: JobOffer)

    fun openJobs(): List<JobRow> = relays.fetch(
        NostrFilter(kinds = listOf(ProtocolKinds.JOB_OFFER), limit = 100),
    ).mapNotNull { event ->
        runCatching { JobRow(event, Offers.fromEvent(event)) }.getOrNull()
    }.filter { it.offer.status == "open" && it.offer.expiresAt > now() }

    fun claim(job: JobRow, payoutAddress: String, attestationIds: List<String>): NostrEvent {
        val event = Assignments.claim(
            privkey, job.event.pubkey,
            Claim(job.event.id, job.offer.escrowId, payoutAddress, attestationIds),
            now(),
        )
        check(relays.publish(event)) { "claim publish failed" }
        return event
    }

    /** My assignments across a job, via the shared deterministic reducer. */
    fun assignments(job: JobRow): List<AssignmentState> {
        val related = relays.fetch(NostrFilter(xTag = job.offer.escrowId, limit = 500))
        return Reducer.reduce(job.event, related, now()).filter { it.worker == pubkey }
    }

    fun submit(job: JobRow, assignment: AssignmentState, answers: List<Answer>): NostrEvent {
        val grantId = checkNotNull(assignment.grantEventId) { "assignment not granted" }
        val event = Assignments.submission(
            privkey, job.event.pubkey,
            Submission(grantId, job.offer.escrowId, answers), now(),
        )
        check(relays.publish(event)) { "submission publish failed" }
        return event
    }

    data class Earning(val escrowId: String, val txid: String, val sats: Long)

    /** Receipts naming me; the wallet layer spot-verifies txids on-chain. */
    fun earnings(): List<Earning> = relays.fetch(
        NostrFilter(kinds = listOf(ProtocolKinds.RECEIPT), pTag = pubkey),
    ).mapNotNull { event ->
        runCatching { Receipts.fromEvent(event) }.getOrNull()?.let { receipt ->
            receipt.lines.firstOrNull { it.worker == pubkey }
                ?.let { Earning(receipt.escrowId, receipt.txid, it.sats) }
        }
    }
}

/**
 * The witness's verification core for on-device co-signing: recomputes the
 * expected payout lines from public events so the signing UI can show
 * exactly what the PSBT MUST pay before the user approves. The platform
 * wallet (bdk) matches the PSBT's outputs against these lines and signs
 * only on equality.
 */
class WitnessSession(private val relays: OkRelayClient) {
    data class CosignSummary(
        val escrowId: String,
        val launcher: String,
        val expectedLines: List<PayoutLine>,
        val resultsHashHex: String,
    )

    fun summarize(escrowId: String): CosignSummary {
        val offerEvent = relays.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.JOB_OFFER), dTag = escrowId),
        ).maxByOrNull { it.createdAt } ?: error("no offer for $escrowId")
        val offer = Offers.fromEvent(offerEvent)
        val reveal = relays.fetch(
            NostrFilter(
                kinds = listOf(ProtocolKinds.VALIDATION),
                authors = listOf(offerEvent.pubkey),
                xTag = escrowId,
            ),
        ).maxByOrNull { it.createdAt } ?: error("no reveal for $escrowId")
        val results = Validations.fromEvent(reveal)

        val recomputed = Validators.validate(
            offer.validation,
            results.rows.map { Validators.Submitted(it.taskKey, it.worker, it.answer) },
        )
        check(recomputed == results.rows) { "reveal does not match recomputation" }

        val claims = relays.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.CLAIM), eTag = offerEvent.id),
        ).associate { it.pubkey to Assignments.parseClaim(it).payoutAddress }
        return CosignSummary(
            escrowId = escrowId,
            launcher = offerEvent.pubkey,
            expectedLines = Validators.payouts(offer.rewardPerTaskSats, recomputed, claims::getValue),
            resultsHashHex = org.hpb.protocol.Validations.resultsHashHex(results),
        )
    }
}

/** The distributed dashboard: event-derived network aggregates. */
class DashboardModel(private val relays: OkRelayClient) {
    data class Snapshot(
        val openJobs: Int,
        val totalRewardPoolSats: Long,
        val payouts: Int,
        val satsPaid: Long,
        val workersPaid: Int,
    )

    fun snapshot(): Snapshot {
        val offers = relays.fetch(NostrFilter(kinds = listOf(ProtocolKinds.JOB_OFFER), limit = 500))
            .mapNotNull { runCatching { Offers.fromEvent(it) }.getOrNull() }
        val receipts = relays.fetch(NostrFilter(kinds = listOf(ProtocolKinds.RECEIPT), limit = 500))
            .mapNotNull { runCatching { Receipts.fromEvent(it) }.getOrNull() }
        val lines = receipts.flatMap { it.lines }
        return Snapshot(
            openJobs = offers.count { it.status == "open" },
            totalRewardPoolSats = offers.filter { it.status == "open" }
                .sumOf { it.rewardPerTaskSats * it.tasks.size },
            payouts = receipts.size,
            satsPaid = lines.sumOf { it.sats },
            workersPaid = lines.map { it.worker }.distinct().size,
        )
    }
}
