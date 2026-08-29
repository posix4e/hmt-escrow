package org.hpb.engine.escrow

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.DUST_LIMIT_SATS
import org.hpb.engine.Descriptors
import org.hpb.engine.EscrowStatus
import org.hpb.engine.MAX_PAYOUT_RECIPIENTS
import org.hpb.engine.Network
import org.hpb.engine.OpReturn
import org.hpb.engine.PsbtPipeline
import org.hpb.engine.Rpc
import org.hpb.engine.TxInput
import org.hpb.engine.TxOutputs
import org.hpb.engine.Vault
import org.hpb.engine.hexBytes
import org.hpb.engine.index.EscrowState
import org.hpb.engine.index.Indexer

/** A party that can sign a PSBT (its key never enters this library). */
fun interface PsbtSigner {
    fun sign(psbt: String): String
}

data class SetupParams(
    val cosigner1: String,
    val cosigner2: String,
    /** null = discover the co-signer's advertised fee from its KVStore entry */
    val cosigner1FeePct: Int? = null,
    val cosigner2FeePct: Int? = null,
    val manifestHash: ByteArray,
    val cancelDelayBlocks: Int? = null,
    val expiryBlocks: Int? = null,
)

data class PayoutRequest(
    val payoutId: String,
    val recipients: List<Pair<String, Long>>,
    val resultsHash: ByteArray,
    val forceComplete: Boolean = false,
)

/** Simple size-based fee at 1 sat/vB (regtest floor); callers may override. */
fun estimateFeeSats(inputs: Int, outputs: Int, satPerVb: Long = 1): Long =
    (60L + inputs * 150L + outputs * 45L) * satPerVb

/** Whether a payout settles the escrow — shared by the builder AND every
 *  co-signer's policy so both derive the identical finalized flag. */
internal fun finalizesEscrow(request: PayoutRequest, state: EscrowState): Boolean =
    request.forceComplete ||
        request.recipients.sumOf { it.second } == state.reservedSats + state.remainingSats

/**
 * The write side of an escrow's lifecycle. Serverless by design: every
 * operation builds transactions locally, signatures come from [PsbtSigner]s
 * held by their parties (Core's descriptorprocesspsbt fills UTXO data during
 * signing), and nothing here talks to any service beyond the participant's
 * own bitcoind.
 */
class Escrows(
    private val network: Network,
    private val rpc: Rpc,
    private val indexer: Indexer,
    private val kv: org.hpb.engine.nostr.KvStore? = null,
) {
    private val pipeline = PsbtPipeline(rpc)
    private val policy = Policy(rpc, indexer, network)

    fun state(escrowId: String): EscrowState = indexer.state(escrowId)

    /** Register a new escrow; no chain transaction yet. Gated on launcher stake. */
    fun create(genesis: Descriptors.Genesis, launcher: String, jobId: String): String {
        indexer.sync()
        val stake = indexer.availableStake(launcher)
        require(stake >= network.minStakeSats) {
            "launcher stake $stake below minimum ${network.minStakeSats}"
        }
        return indexer.registerEscrow(genesis, launcher, jobId, nowSeconds())
    }

    /** Sweep genesis funds into the vault with the SETUP commitment. */
    fun setup(escrowId: String, params: SetupParams, genesisSigner: PsbtSigner): String {
        indexer.sync()
        val vault = buildAndRegisterVault(escrowId, params)
        val utxos = indexer.unspentUtxos(escrowId, "genesis")
        require(utxos.isNotEmpty()) { "escrow has no genesis funds to set up" }
        val total = utxos.sumOf { it.sats }
        val fee = estimateFeeSats(utxos.size, 2)
        val tag = OpReturn.Setup(
            escrowId.hexBytes(), params.manifestHash,
            resolveFee(params.cosigner1FeePct, params.cosigner1),
            resolveFee(params.cosigner2FeePct, params.cosigner2),
        )
        val psbt = pipeline.create(
            utxos.map { TxInput(it.txid, it.vout) },
            TxOutputs(
                payments = listOf(vault.address to total - fee),
                opReturn = OpReturn.encode(tag),
            ),
        )
        return pipeline.broadcast(pipeline.finalize(genesisSigner.sign(psbt)))
    }

    private fun buildAndRegisterVault(escrowId: String, params: SetupParams): Vault {
        val vault = Descriptors.vault(
            indexer.escrowRow(escrowId).getValue("launcher")!!,
            params.cosigner1,
            params.cosigner2,
            params.cancelDelayBlocks ?: network.cancelDelayBlocks,
            currentHeight() + (params.expiryBlocks ?: network.expiryBlocks),
            network,
        )
        indexer.registerVault(escrowId, vault)
        return vault
    }

    /** Record a reservation locally (relay distribution arrives with the Nostr layer). */
    fun reserve(escrowId: String, eventId: String, signer: String, sats: Long, seq: Long): Boolean =
        indexer.ingestReservation(
            Indexer.Reservation(eventId, escrowId, signer, sats, seq, nowSeconds()),
            verified = true,
        )

    /** Idempotency lookup: the confirmed txid for a payout id, if it exists. */
    fun payoutTxid(escrowId: String, payoutId: String): String? {
        indexer.sync()
        return indexer.payoutTxid(escrowId, OpReturn.payoutIdHash(payoutId))
    }

    /**
     * Build the payout PSBT (no signatures). Returns null when the payout id
     * is already confirmed — replays are no-ops per protocol.
     */
    fun buildPayout(escrowId: String, request: PayoutRequest): String? {
        if (payoutTxid(escrowId, request.payoutId) != null) return null
        validateRequest(request)
        val state = indexer.state(escrowId)
        require(request.recipients.sumOf { it.second } <= state.reservedSats) {
            "payout exceeds reserved funds"
        }
        val tag = OpReturn.Payout(
            OpReturn.payoutIdHash(request.payoutId), request.resultsHash,
            request.forceComplete, finalized = finalizes(request, state),
        )
        val utxos = indexer.unspentUtxos(escrowId, "vault")
        // Core happily builds a zero-input PSBT, so guard explicitly: paying
        // before the setup tx confirms must fail loudly, not produce garbage.
        require(utxos.isNotEmpty()) { "no confirmed vault funds — wait for setup to confirm" }
        return pipeline.create(
            utxos.map { TxInput(it.txid, it.vout) },
            TxOutputs(
                payments = payoutOutputs(escrowId, request, state),
                opReturn = OpReturn.encode(tag),
            ),
        )
    }

    private fun validateRequest(request: PayoutRequest) {
        require(request.recipients.isNotEmpty()) { "no recipients" }
        require(request.recipients.size <= MAX_PAYOUT_RECIPIENTS) {
            "more than $MAX_PAYOUT_RECIPIENTS recipients; split into multiple payout ids"
        }
        require(request.recipients.all { it.second >= DUST_LIMIT_SATS }) {
            "recipient amount below dust floor $DUST_LIMIT_SATS"
        }
    }

    private fun finalizes(request: PayoutRequest, state: EscrowState): Boolean =
        finalizesEscrow(request, state)

    /** Recipients + (finalize: fee & refund outputs | else: vault change). */
    private fun payoutOutputs(
        escrowId: String,
        request: PayoutRequest,
        state: EscrowState,
    ): List<Pair<String, Long>> {
        val vault = indexer.vaultOf(escrowId, network)
        val paying = request.recipients.sumOf { it.second }
        return if (finalizes(request, state)) {
            request.recipients + finalizeOutputs(escrowId, vault, state, paying)
        } else {
            val fee = estimateFeeSats(1, request.recipients.size + 2)
            request.recipients + (vault.address to (state.balanceSats - paying - fee))
        }
    }

    /** Co-signer fees (dust-rolled into the refund) + launcher refund pay the mining fee. */
    private fun finalizeOutputs(
        escrowId: String,
        vault: Vault,
        state: EscrowState,
        paying: Long,
    ): List<Pair<String, Long>> {
        val (fee1, fee2) = indexer.feeSnapshot(escrowId)
        val outputs = ArrayList<Pair<String, Long>>()
        var refund = state.balanceSats - paying
        listOf(
            vault.cosigner1 to state.totalFundedSats * fee1 / 100,
            vault.cosigner2 to state.totalFundedSats * fee2 / 100,
        ).forEach { (key, fee) ->
            if (fee >= DUST_LIMIT_SATS) {
                outputs += identityAddress(key) to fee
                refund -= fee
            }
        }
        refund -= estimateFeeSats(1, outputs.size + 3)
        require(refund >= DUST_LIMIT_SATS) {
            "insufficient remaining funds for mining fee — fund the escrow with a buffer"
        }
        return outputs + (identityAddress(vault.launcher) to refund)
    }

    /** Every co-signer runs this before signing; see [Policy]. */
    fun checkPayout(escrowId: String, psbt: String, request: PayoutRequest) =
        policy.checkPayout(escrowId, psbt, request)

    fun broadcastPayout(signedPsbts: List<String>): String =
        pipeline.broadcast(pipeline.finalize(pipeline.combine(signedPsbts)))

    /** Identity key -> its plain tr() P2TR address (payout/fee destination). */
    fun identityAddress(xonly: String): String = Descriptors.genesis(xonly, network).address

    /** Launched: sweep genesis back to the launcher. Later: ToCancel record. */
    fun requestCancellation(escrowId: String, genesisSigner: PsbtSigner?): String? {
        if (indexer.state(escrowId).status != EscrowStatus.LAUNCHED) {
            indexer.ingestCancelRequest(
                "cancel-$escrowId-${nowSeconds()}", escrowId,
                indexer.escrowRow(escrowId).getValue("launcher")!!, nowSeconds(), verified = true,
            )
            return null
        }
        requireNotNull(genesisSigner) { "genesis signer required to sweep a Launched escrow" }
        return sweepGenesis(escrowId, genesisSigner)
    }

    private fun sweepGenesis(escrowId: String, signer: PsbtSigner): String {
        indexer.sync()
        val utxos = indexer.unspentUtxos(escrowId, "genesis")
        require(utxos.isNotEmpty()) { "nothing to sweep" }
        val launcher = indexer.escrowRow(escrowId).getValue("launcher")!!
        val fee = estimateFeeSats(utxos.size, 2)
        val psbt = pipeline.create(
            utxos.map { TxInput(it.txid, it.vout) },
            TxOutputs(
                payments = listOf(identityAddress(launcher) to utxos.sumOf { it.sats } - fee),
                opReturn = OpReturn.encode(OpReturn.Cancel(escrowId.hexBytes())),
            ),
        )
        return pipeline.broadcast(pipeline.finalize(signer.sign(psbt)))
    }

    /** Cooperative cancel: refund everything to the launcher (needs 2-of-3). */
    fun buildCancel(escrowId: String): String {
        val state = indexer.state(escrowId)
        require(state.status == EscrowStatus.TO_CANCEL) { "cancel requires ToCancel status" }
        require(state.reservedSats == 0L) { "cancel requires zero reserved funds" }
        return buildVaultRefund(escrowId, OpReturn.Cancel(escrowId.hexBytes()), all = true)
    }

    /** Cooperative withdraw of unobligated funds; obligations stay vaulted. */
    fun buildWithdraw(escrowId: String): String {
        val state = indexer.state(escrowId)
        require(state.remainingSats > DUST_LIMIT_SATS * 2) { "nothing withdrawable" }
        return buildVaultRefund(escrowId, OpReturn.Withdraw, all = false)
    }

    private fun buildVaultRefund(escrowId: String, tag: OpReturn.Record, all: Boolean): String {
        val state = indexer.state(escrowId)  // state() syncs first
        val vault = indexer.vaultOf(escrowId, network)
        val utxos = indexer.unspentUtxos(escrowId, "vault")
        val launcher = indexer.escrowRow(escrowId).getValue("launcher")!!
        val fee = estimateFeeSats(utxos.size, 3)
        val payments = if (all) {
            listOf(identityAddress(launcher) to state.balanceSats - fee)
        } else {
            listOf(
                identityAddress(launcher) to state.remainingSats - fee,
                vault.address to state.balanceSats - state.remainingSats,
            )
        }
        return pipeline.create(
            utxos.map { TxInput(it.txid, it.vout) },
            TxOutputs(payments = payments, opReturn = OpReturn.encode(tag)),
        )
    }

    /** Explicit fee, else the co-signer's KVStore-advertised fee, else zero. */
    private fun resolveFee(explicit: Int?, cosigner: String): Int =
        explicit ?: kv?.feePct(cosigner) ?: 0

    private fun currentHeight(): Int = rpc.call("getblockcount").jsonPrimitive.int

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
