package org.hpb.roles

import org.hpb.engine.Descriptors
import org.hpb.engine.Keys
import org.hpb.engine.escrow.PayoutRequest
import org.hpb.engine.escrow.PsbtSigner
import org.hpb.engine.hex
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.Assignments
import org.hpb.protocol.Attestations
import org.hpb.protocol.Envelopes
import org.hpb.protocol.EscrowResults
import org.hpb.protocol.JobOffer
import org.hpb.protocol.Offers
import org.hpb.protocol.PayoutLine
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Receipt
import org.hpb.protocol.Receipts
import org.hpb.protocol.Records
import org.hpb.protocol.Reserve
import org.hpb.protocol.SignRequest
import org.hpb.protocol.Validations
import org.hpb.protocol.CvatCommitments
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.Validators
import org.hpb.protocol.WorkerToolsCodec

/**
 * The launcher runs the whole job from its own client — publish, grant,
 * collect, reveal, validate, pay — with a witness co-signing the payout.
 * Zero servers anywhere in this flow.
 */
class LauncherRole(private val ctx: RoleContext) {

    data class LaunchedJob(
        val escrowId: String,
        val genesisKey: ByteArray,
        val genesisAddress: String,
    )

    /** Register the escrow (stake-gated); fund the returned address next. */
    fun createEscrow(jobId: String): LaunchedJob {
        val genesisKey = Keys.genesisPrivateKey(ctx.privkey, jobId, 0, ctx.network)
        val genesis = Descriptors.genesis(
            org.hpb.engine.Secp.xonlyHex(genesisKey), ctx.network,
        )
        val escrowId = ctx.escrows.create(genesis, ctx.pubkey, jobId)
        return LaunchedJob(escrowId, genesisKey, genesis.address)
    }

    /** Sweep funds into the vault, announce the escrow, publish the offer. */
    fun setupAndOffer(
        job: LaunchedJob,
        offer: JobOffer,
        cosigner1: String,
        cosigner2: String,
        cosignerFees: Pair<Int, Int>,
    ): NostrEvent {
        ctx.escrows.setup(
            job.escrowId,
            org.hpb.engine.escrow.SetupParams(
                cosigner1 = cosigner1,
                cosigner2 = cosigner2,
                cosigner1FeePct = cosignerFees.first,
                cosigner2FeePct = cosignerFees.second,
                manifestHash = offer.manifestHash(),
            ),
            PsbtSigner { ctx.pipeline.sign(it, "tr(${org.hpb.engine.wif(job.genesisKey, ctx.network)})") },
        )
        publishAnnounce(job)
        val offerEvent = Offers.toEvent(ctx.privkey, offer, ctx.now())
        check(ctx.nostr.publish(offerEvent)) { "offer publish failed" }
        return offerEvent
    }

    private fun publishAnnounce(job: LaunchedJob) {
        val row = ctx.indexer.escrowRow(job.escrowId)
        val vault = ctx.indexer.vaultOf(job.escrowId, ctx.network)
        check(
            ctx.nostr.publish(
                Records.announce(
                    ctx.privkey,
                    org.hpb.protocol.Announce(
                        escrowId = job.escrowId,
                        genesisXonly = org.hpb.engine.Secp.xonlyHex(job.genesisKey),
                        cosigner1 = vault.cosigner1,
                        cosigner2 = vault.cosigner2,
                        cancelDelayBlocks = vault.cancelDelayBlocks,
                        expiryHeight = vault.expiryHeight,
                    ),
                    ctx.now(),
                ),
            ),
        ) { "announce publish failed for ${row["genesis_address"]}" }
    }

    /** Grant up to maxWorkers valid claims (KYC-checked); reject the rest. */
    fun grantClaims(offerEvent: NostrEvent, maxWorkers: Int): List<NostrEvent> {
        val offer = Offers.fromEvent(offerEvent)
        val claims = ctx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.CLAIM), eTag = offerEvent.id),
        ).sortedWith(compareBy({ it.createdAt }, { it.id }))
        var granted = 0
        return claims.map { claimEvent ->
            val refusal = refuse(offer, claimEvent, granted, maxWorkers)
            val accept = refusal == null
            if (accept) granted++
            val grant = Assignments.grant(
                ctx.privkey, claimEvent.pubkey,
                org.hpb.protocol.Grant(
                    claimEvent.id, offer.escrowId, accept,
                    if (accept) offer.tasks.map { it.key } else emptyList(),
                    expiresAt = ctx.now() + GRANT_TTL_SECONDS,
                    reason = refusal,
                ),
                ctx.now(),
            )
            check(ctx.nostr.publish(grant)) { "grant publish failed" }
            grant
        }
    }

    /**
     * Why this claim cannot be granted, or null to grant it.
     *
     * Naming the gate matters: a bare "not eligible" tells a worker nothing
     * about whether to fix their KYC, sign into a tool, or simply try a
     * different job.
     */
    private fun refuse(
        offer: JobOffer,
        claimEvent: NostrEvent,
        granted: Int,
        maxWorkers: Int,
    ): String? = when {
        granted >= maxWorkers -> "all assignments taken"
        !kycSatisfied(offer, claimEvent) -> "kyc attestation missing or unaccepted"
        !toolSatisfied(offer, claimEvent) -> "worker has not declared support for ${requiredTools(offer)}"
        else -> null
    }

    /** The tools an offer's tasks live in; empty for ordinary inline work. */
    private fun requiredTools(offer: JobOffer): Set<String> =
        offer.tasks.mapNotNull { ExternalWork.workSource(it.question)?.tool }.toSet()

    /**
     * Only enforced for work that lives in another tool. Inline jobs — and every
     * client that predates capability declarations — are unaffected.
     */
    private fun toolSatisfied(offer: JobOffer, claimEvent: NostrEvent): Boolean {
        val required = requiredTools(offer)
        if (required.isEmpty()) return true
        val declared = WorkerToolsCodec.latest(
            ctx.nostr.fetch(
                NostrFilter(
                    kinds = listOf(ProtocolKinds.WORKER_TOOLS),
                    authors = listOf(claimEvent.pubkey),
                    dTag = WorkerToolsCodec.D_TAG,
                    limit = FETCH_LIMIT,
                ),
            ),
        )[claimEvent.pubkey].orEmpty()
        return declared.containsAll(required)
    }

    private fun kycSatisfied(offer: JobOffer, claimEvent: NostrEvent): Boolean {
        if (!offer.kyc.required) return true
        val claim = Assignments.parseClaim(claimEvent)
        if (claim.attestationEventIds.isEmpty()) return false
        val events = ctx.nostr.fetch(NostrFilter(ids = claim.attestationEventIds))
        return events.any { Attestations.satisfies(it, offer.kyc, claimEvent.pubkey, ctx.now()) }
    }

    /** Decrypt submissions into the revealed set, grant-scoped: only rows
     *  from granted assignments covering the task, one per (worker, task) —
     *  a worker repeating an answer must not earn one reward per repeat. */
    fun collectSubmissions(offerEvent: NostrEvent): List<Validators.Submitted> {
        val offer = Offers.fromEvent(offerEvent)
        val related = ctx.nostr.fetch(NostrFilter(xTag = offer.escrowId, limit = 500))
        val assignments = org.hpb.protocol.Reducer.reduce(offerEvent, related, ctx.now())
        val rows = related.filter { it.kind == ProtocolKinds.SUBMISSION }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .flatMap { event ->
                runCatching { Assignments.decryptSubmission(event, ctx.privkey) }
                    .getOrDefault(emptyList())
                    .map { Validators.Submitted(it.taskKey, event.pubkey, it.answer) }
            }
        return Validators.scoped(rows, assignments)
    }

    /** Validate, publish the reveal, and reserve the payout total. */
    /**
     * [pulled] carries annotations read back out of an external tool, keyed by
     * (worker, task). Where a worker asserted completion rather than an answer,
     * its row is replaced by what was pulled — but only if those bytes hash to
     * the worker's own public commitment, which every witness re-checks. Empty
     * for ordinary inline jobs, which are unaffected.
     */
    fun revealAndReserve(
        offerEvent: NostrEvent,
        submitted: List<Validators.Submitted>,
        pulled: Map<Pair<String, String>, String> = emptyMap(),
    ): EscrowResults {
        val offer = Offers.fromEvent(offerEvent)
        val resolved = ExternalWork.substitute(submitted, pulled, commitments(offer.escrowId))
        val results = EscrowResults(offer.escrowId, Validators.validate(offer.validation, resolved))
        check(ctx.nostr.publish(Validations.toEvent(ctx.privkey, results, ctx.now()))) {
            "reveal publish failed"
        }
        val total = results.rows.count { it.accepted } * offer.rewardPerTaskSats
        check(
            ctx.nostr.publish(
                Records.reserve(ctx.privkey, Reserve(offer.escrowId, total, 1), ctx.now()),
            ),
        ) { "reserve publish failed" }
        check(ctx.escrows.reserve(offer.escrowId, "local-${offer.escrowId}", ctx.pubkey, total, 1)) {
            "local reservation rejected"
        }
        return results
    }

    private fun commitments(escrowId: String): Map<Pair<String, String>, String> =
        CvatCommitments.index(
            ctx.nostr.fetch(
                NostrFilter(
                    kinds = listOf(ProtocolKinds.CVAT_COMMITMENT),
                    xTag = escrowId,
                    limit = FETCH_LIMIT,
                ),
            ),
        )

    data class PendingPayout(val request: PayoutRequest, val myPsbt: String, val lines: List<PayoutLine>)

    /** Build + self-sign the payout, and ask the witness for the second leg. */
    fun requestCosign(
        offerEvent: NostrEvent,
        results: EscrowResults,
        payoutAddressOf: (String) -> String,
    ): PendingPayout {
        val offer = Offers.fromEvent(offerEvent)
        val lines = Validators.payouts(offer.rewardPerTaskSats, results.rows, payoutAddressOf)
        val request = PayoutRequest(
            payoutId = "${offer.escrowId}/1",
            recipients = lines.map { it.address to it.sats },
            resultsHash = results.resultsHash(),
            forceComplete = true,
        )
        val psbt = checkNotNull(ctx.escrows.buildPayout(offer.escrowId, request)) {
            "payout already confirmed"
        }
        val witness = ctx.indexer.vaultOf(offer.escrowId, ctx.network).cosigner1
        check(
            ctx.nostr.publish(
                Envelopes.signRequest(
                    ctx.privkey, witness,
                    SignRequest(offer.escrowId, request.payoutId, psbt), ctx.now(),
                ),
            ),
        ) { "sign request publish failed" }
        return PendingPayout(request, ctx.vaultSigner(offer.escrowId).sign(psbt), lines)
    }

    /** Collect the witness signature, broadcast, publish the receipt. A
     *  refusal can be transient (the witness may not have seen the reveal
     *  yet), so any successful co-signature for this payout id wins. */
    fun finishPayout(escrowId: String, pending: PendingPayout): Receipt {
        val responses = ctx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.ENVELOPE), pTag = ctx.pubkey, xTag = escrowId),
        ).mapNotNull { event -> signResponseOf(event, pending.request.payoutId) }
        val witnessPsbt = checkNotNull(responses.firstNotNullOfOrNull { it.psbt }) {
            "witness refused or missing: ${responses.firstNotNullOfOrNull { it.error } ?: "no response yet"}"
        }
        val txid = ctx.escrows.broadcastPayout(listOf(pending.myPsbt, witnessPsbt))
        val receipt = Receipt(
            escrowId, pending.request.payoutId, txid,
            pending.lines, final = true,
        )
        check(ctx.nostr.publish(Receipts.toEvent(ctx.privkey, receipt, ctx.now()))) {
            "receipt publish failed"
        }
        return receipt
    }

    private fun signResponseOf(event: NostrEvent, payoutId: String) = runCatching {
        val (type, body) = Envelopes.open(event, ctx.privkey)
        if (type == "psbt_sign_response") {
            Envelopes.parseSignResponse(event, body).takeIf { it.payoutId == payoutId }
        } else {
            null
        }
    }.getOrNull()

    private companion object {
        const val GRANT_TTL_SECONDS = 3600L
        const val FETCH_LIMIT = 500
    }
}
