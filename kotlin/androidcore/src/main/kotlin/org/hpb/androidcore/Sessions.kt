package org.hpb.androidcore

import org.hpb.engine.Secp
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.Answer
import org.hpb.protocol.AssignmentState
import org.hpb.protocol.Assignments
import org.hpb.protocol.Claim
import org.hpb.protocol.CvatAccessCodec
import org.hpb.protocol.CvatAccessGrant
import org.hpb.protocol.CvatAccessRequest
import org.hpb.protocol.CvatCommitment
import org.hpb.protocol.CvatCommitments
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.JobOffer
import org.hpb.protocol.Offers
import org.hpb.protocol.PayoutLine
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Receipts
import org.hpb.protocol.Reducer
import org.hpb.protocol.Submission
import org.hpb.protocol.Validations
import org.hpb.protocol.Validators
import org.hpb.protocol.WorkCompletion
import org.hpb.protocol.WorkerToolsCodec

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

    // ---- work that lives in another tool -------------------------------
    //
    // The same three steps the iOS client performs, kept here so the Compose
    // shell and the web labeler share one implementation rather than each
    // growing their own.

    /**
     * Declare which tools this control plane can *verify results for*.
     *
     * Not "can execute" — execution may be routed to a desktop or an agent.
     * A launcher refuses external work to a worker that has not declared the
     * tool, which is what stops a client claiming a job it can never finish.
     */
    fun declareTools(tools: List<String>): NostrEvent {
        val event = WorkerToolsCodec.toEvent(privkey, tools, now())
        check(relays.publish(event)) { "tool declaration publish failed" }
        return event
    }

    /** Ask the launcher to admit this worker's own account in the tool. */
    fun requestToolAccess(job: JobRow, claimEventId: String, accountRef: String): NostrEvent {
        val event = CvatAccessCodec.request(
            privkey, job.event.pubkey,
            CvatAccessRequest(claimEventId, job.offer.escrowId, accountRef), now(),
        )
        check(relays.publish(event)) { "access request publish failed" }
        return event
    }

    /** The access this worker has been granted for an escrow, if any. */
    fun toolAccess(escrowId: String): CvatAccessGrant? = relays.fetch(
        NostrFilter(kinds = listOf(ProtocolKinds.CVAT_ACCESS_GRANT), pTag = pubkey, limit = 100),
    ).firstNotNullOfOrNull { event ->
        runCatching { CvatAccessCodec.parseGrant(event, privkey) }.getOrNull()
            ?.takeIf { it.escrowId == escrowId }
    }

    /** My own claim for an escrow, which the access request has to reference. */
    fun claimEventId(escrowId: String): String? = relays.fetch(
        NostrFilter(
            kinds = listOf(ProtocolKinds.CLAIM), authors = listOf(pubkey),
            xTag = escrowId, limit = 10,
        ),
    ).minByOrNull { it.createdAt }?.id

    /**
     * Commit publicly to what this worker's own work hashed to, then return the
     * answer that asserts completion.
     *
     * The commitment must be published *before* the launcher reveals anything —
     * it is what lets a witness refuse a reveal the worker never made.
     */
    fun commitAndAnswer(escrowId: String, taskKey: String, ref: String, canonical: String): Answer {
        val hash = ExternalWork.hashOf(canonical)
        val commitment = CvatCommitments.toEvent(
            privkey, CvatCommitment(escrowId, taskKey, pubkey, hash), now(),
        )
        check(relays.publish(commitment)) { "commitment publish failed" }
        return Answer(taskKey, ExternalWork.answer(WorkCompletion(ref, hash)))
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
