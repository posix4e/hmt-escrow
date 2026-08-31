package org.hpb.roles

import org.hpb.engine.Descriptors
import org.hpb.engine.hex
import org.hpb.engine.escrow.PayoutRequest
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.Envelopes
import org.hpb.protocol.Offers
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Records
import org.hpb.protocol.SignRequest
import org.hpb.protocol.SignResponse
import org.hpb.protocol.Validations
import org.hpb.protocol.CvatCommitments
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.Validators

/**
 * The witness co-signer: the trust-critical role, run from anyone's device.
 * It verifies from FIRST PRINCIPLES — derives the escrow from the announce,
 * replays reservations into its OWN index, recomputes validation and the
 * payout list from the reveal, and only then policy-checks and signs the
 * PSBT. An envelope is a request, never authority.
 */
class WitnessRole(private val ctx: RoleContext) {
    private val handled = HashSet<String>()
    private val refused = HashSet<String>()

    /** Handle every pending sign request addressed to this key; returns the
     *  number SIGNED. A request that fails verification is retried on later
     *  polls — the failure may be transient (relays are eventually
     *  consistent, so the reveal/reservation can arrive after the request). */
    fun serveOnce(): Int {
        val envelopes = ctx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.ENVELOPE), pTag = ctx.pubkey),
        ).filter { it.id !in handled }
        var signed = 0
        for (event in envelopes) {
            if (serveEnvelope(event)) signed++
        }
        return signed
    }

    private fun serveEnvelope(event: NostrEvent): Boolean {
        val request = signRequestOf(event)
        if (request == null) {
            handled.add(event.id)
            return false
        }
        val outcome = runCatching { verifyAndSign(request) }
        if (outcome.isSuccess) {
            handled.add(event.id)
        } else if (!refused.add(event.id)) {
            return false // refusal already published; keep retrying quietly
        }
        val response = outcome.getOrElse {
            SignResponse(request.escrowId, request.payoutId, null, it.message)
        }
        ctx.nostr.publish(Envelopes.signResponse(ctx.privkey, event.pubkey, response, ctx.now()))
        return outcome.isSuccess
    }

    private fun signRequestOf(event: NostrEvent): SignRequest? = runCatching {
        val (type, body) = Envelopes.open(event, ctx.privkey)
        if (type == "psbt_sign_request") Envelopes.parseSignRequest(event, body) else null
    }.getOrNull()

    private fun verifyAndSign(request: SignRequest): SignResponse {
        learnEscrow(request.escrowId)
        replayReservations(request.escrowId)
        val expected = recomputeExpectedPayout(request)
        ctx.escrows.checkPayout(request.escrowId, request.psbt, expected)
        val signed = ctx.vaultSigner(request.escrowId).sign(request.psbt)
        return SignResponse(request.escrowId, request.payoutId, signed)
    }

    /** Derive descriptors from the launcher-signed announce; verify the id binding. */
    private fun learnEscrow(escrowId: String) {
        if (runCatching { ctx.indexer.vaultOf(escrowId, ctx.network) }.isSuccess) return
        val announceEvent = ctx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.RECORD), xTag = escrowId),
        ).firstOrNull { Records.typeOf(it) == "announce" }
            ?: error("no announce record for $escrowId")
        val announce = Records.parseAnnounce(announceEvent)
        val genesis = Descriptors.genesis(announce.genesisXonly, ctx.network)
        val derivedId = org.hpb.engine.Keys.escrowId(genesis.scriptPubKey)
        check(derivedId.hex() == escrowId) { "announce does not match escrow id" }
        ctx.indexer.registerEscrow(genesis, announceEvent.pubkey, escrowId, announceEvent.createdAt)
        ctx.indexer.registerVault(
            escrowId,
            Descriptors.vault(
                announceEvent.pubkey, announce.cosigner1, announce.cosigner2,
                announce.cancelDelayBlocks, announce.expiryHeight, ctx.network,
            ),
        )
        // The setup transaction is already on chain, and the scanner only ever
        // moves forward — so without looking again this escrow would never
        // acquire the manifest hash that recomputeExpectedPayout must check,
        // and every co-sign request for it would be refused.
        ctx.indexer.rescanFrom(0)
        ctx.indexer.sync()
    }

    private fun replayReservations(escrowId: String) {
        val launcher = ctx.indexer.escrowRow(escrowId).getValue("launcher")!!
        ctx.nostr.fetch(NostrFilter(kinds = listOf(ProtocolKinds.RECORD), xTag = escrowId))
            .filter { Records.typeOf(it) == "reserve" && it.pubkey == launcher }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .forEach { event ->
                val reserve = Records.parseReserve(event)
                ctx.indexer.ingestReservation(
                    Indexer.Reservation(
                        event.id, escrowId, event.pubkey,
                        reserve.sats, reserve.seq, event.createdAt,
                    ),
                    verified = true,
                )
            }
    }

    /** Recompute validation + payout list from public events — never trust the PSBT. */
    private fun recomputeExpectedPayout(request: SignRequest): PayoutRequest {
        val launcher = ctx.indexer.escrowRow(request.escrowId).getValue("launcher")!!
        val offerEvent = ctx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.JOB_OFFER), authors = listOf(launcher), dTag = request.escrowId),
        ).maxByOrNull { it.createdAt } ?: error("no offer for ${request.escrowId}")
        val offer = Offers.fromEvent(offerEvent)
        // The SETUP tx committed sha256(manifest) on-chain; a re-published
        // offer with different terms (reward, tasks, validation) must not be
        // able to change what this witness signs for.
        check(offer.manifestHash().hex() == ctx.indexer.escrowRow(request.escrowId)["manifest_hash"]) {
            "offer does not match the on-chain manifest commitment"
        }
        val reveal = ctx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.VALIDATION), authors = listOf(launcher), xTag = request.escrowId),
        ).maxByOrNull { it.createdAt } ?: error("no reveal for ${request.escrowId}")
        val results = Validations.fromEvent(reveal)

        // Work done in another tool is only as trustworthy as the worker's own
        // public commitment: the launcher administers CVAT, so without this it
        // could reveal annotations the worker never made. Submissions are
        // encrypted to the launcher and unreadable here, which is exactly why
        // the commitment is a separate public event.
        val commitments = CvatCommitments.index(
            ctx.nostr.fetch(
                NostrFilter(
                    kinds = listOf(ProtocolKinds.CVAT_COMMITMENT),
                    xTag = request.escrowId,
                    limit = FETCH_LIMIT,
                ),
            ),
        )
        check(ExternalWork.revealMatchesCommitments(results.rows, commitments)) {
            "reveal contains annotations the worker never committed to"
        }

        val submitted = results.rows.map { Validators.Submitted(it.taskKey, it.worker, it.answer) }
        // grant-scoping re-checked from public events: a reveal with
        // duplicate or ungranted rows would inflate the payout list
        val related = ctx.nostr.fetch(NostrFilter(xTag = request.escrowId, limit = 500))
        val assignments = org.hpb.protocol.Reducer.reduce(offerEvent, related, ctx.now())
        check(Validators.scoped(submitted, assignments) == submitted) {
            "reveal contains duplicate or ungranted rows"
        }
        val recomputed = Validators.validate(offer.validation, submitted)
        check(recomputed == results.rows) { "reveal's acceptance flags do not match recomputation" }

        val lines = Validators.payouts(offer.rewardPerTaskSats, recomputed, payoutAddresses(offerEvent))
        return PayoutRequest(
            payoutId = request.payoutId,
            recipients = lines.map { it.address to it.sats },
            resultsHash = results.resultsHash(),
            forceComplete = true,
        )
    }

    private fun payoutAddresses(offerEvent: NostrEvent): (String) -> String {
        val claims = ctx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.CLAIM), eTag = offerEvent.id),
        ).associate {
            it.pubkey to org.hpb.protocol.Assignments.parseClaim(it).payoutAddress
        }
        return { worker -> claims.getValue(worker) }
    }

    private companion object {
        const val FETCH_LIMIT = 500
    }
}
