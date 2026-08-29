package org.hpb.engine.escrow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.DUST_LIMIT_SATS
import org.hpb.engine.MAX_PAYOUT_RECIPIENTS
import org.hpb.engine.Network
import org.hpb.engine.OpReturn
import org.hpb.engine.Rpc
import org.hpb.engine.btcToSats
import org.hpb.engine.index.Indexer

class PolicyViolation(message: String) : IllegalStateException(message)

/**
 * Signer-side payout policy — the protocol's substitute for contract
 * enforcement. EVERY co-signer re-checks a payout PSBT against its OWN chain
 * view before signing; a violation refuses the signature. This is policy,
 * not consensus: any 2-of-3 collusion can bypass it (documented trust model).
 */
class Policy(
    private val rpc: Rpc,
    private val indexer: Indexer,
    private val network: Network,
) {
    private data class Out(val address: String?, val sats: Long, val scriptHex: String)

    fun checkPayout(escrowId: String, psbt: String, request: PayoutRequest) {
        indexer.sync()
        val tx = rpc.call("decodepsbt", JsonPrimitive(psbt)).jsonObject
            .getValue("tx").jsonObject
        val inputSats = checkInputs(escrowId, tx)
        val outputs = outputsOf(tx)
        val finalizing = finalizesEscrow(request, indexer.state(escrowId))
        checkCommitment(request, outputs, finalizing)
        checkOutputAllocation(escrowId, request, outputs, finalizing)
        checkLedger(escrowId, request, inputSats, outputs.sumOf { it.sats })
    }

    /** All inputs must be this escrow's unspent vault UTXOs; returns their sum. */
    private fun checkInputs(escrowId: String, tx: JsonObject): Long {
        if (tx.getValue("vin").jsonArray.isEmpty()) {
            throw PolicyViolation("payout spends no inputs")
        }
        val vaultUtxos = indexer.unspentUtxos(escrowId, "vault")
            .associateBy({ it.txid to it.vout }, { it.sats })
        var total = 0L
        for (vin in tx.getValue("vin").jsonArray) {
            val outpoint = vin.jsonObject.getValue("txid").jsonPrimitive.content to
                vin.jsonObject.getValue("vout").jsonPrimitive.int
            total += vaultUtxos[outpoint]
                ?: throw PolicyViolation("input $outpoint is not an unspent vault UTXO")
        }
        return total
    }

    private fun outputsOf(tx: JsonObject): List<Out> =
        tx.getValue("vout").jsonArray.map { out ->
            val obj = out.jsonObject
            val spk = obj.getValue("scriptPubKey").jsonObject
            Out(
                spk["address"]?.jsonPrimitive?.content,
                btcToSats(obj.getValue("value").jsonPrimitive.content),
                spk.getValue("hex").jsonPrimitive.content,
            )
        }

    /** The tx must carry exactly one HMTB PAYOUT record committing to THIS
     *  request — without it, a confirmed spend would leave the payout id
     *  unrecorded and the reservation replayable. */
    private fun checkCommitment(request: PayoutRequest, outputs: List<Out>, finalizing: Boolean) {
        val tag = outputs.mapNotNull { OpReturn.decodeScriptPubKey(it.scriptHex) }
            .filterIsInstance<OpReturn.Payout>().singleOrNull()
            ?: throw PolicyViolation("payout must carry exactly one HMTB PAYOUT record")
        val commits = tag.payoutIdHash.contentEquals(OpReturn.payoutIdHash(request.payoutId)) &&
            tag.resultsHash.contentEquals(request.resultsHash) &&
            tag.forceComplete == request.forceComplete &&
            tag.finalized == finalizing
        if (!commits) throw PolicyViolation("HMTB PAYOUT record does not commit to this request")
    }

    /** The COMPLETE output multiset must be accounted for: the claimed
     *  recipients (exact amounts), the exact SETUP-committed co-signer fees
     *  at finalize, the payout record, and a single sink for the remainder —
     *  vault change mid-job (reserved obligations stay vaulted), the
     *  launcher refund at finalize. Any other output is a surplus drain the
     *  co-signer must refuse, wherever it points. */
    private fun checkOutputAllocation(
        escrowId: String,
        request: PayoutRequest,
        outputs: List<Out>,
        finalizing: Boolean,
    ) {
        if (request.recipients.size > MAX_PAYOUT_RECIPIENTS) {
            throw PolicyViolation("too many recipients")
        }
        val vault = indexer.vaultOf(escrowId, network)
        val pool = outputs.mapNotNull { out -> out.address?.let { it to out.sats } }.toMutableList()
        consume(pool, request.recipients, "recipient")
        if (finalizing) consume(pool, expectedFees(escrowId, vault), "co-signer fee")
        checkResidualSink(pool, if (finalizing) identity(vault.launcher) else vault.address)
    }

    private fun checkResidualSink(pool: List<Pair<String, Long>>, sink: String) {
        pool.forEach { (address, _) ->
            if (address != sink) throw PolicyViolation("unexpected output to $address")
        }
    }

    private fun consume(
        pool: MutableList<Pair<String, Long>>,
        expected: List<Pair<String, Long>>,
        what: String,
    ) {
        for (entry in expected) {
            val match = pool.indexOf(entry)
            if (match < 0) throw PolicyViolation("$what ${entry.first} not paid as claimed")
            pool.removeAt(match)
        }
    }

    /** The SETUP-committed fee snapshot, as exact expected outputs (>= dust). */
    private fun expectedFees(escrowId: String, vault: org.hpb.engine.Vault): List<Pair<String, Long>> {
        val (fee1, fee2) = indexer.feeSnapshot(escrowId)
        val totalFunded = indexer.state(escrowId).totalFundedSats
        return listOf(vault.cosigner1 to fee1, vault.cosigner2 to fee2)
            .map { (key, pct) -> identity(key) to totalFunded * pct / 100 }
            .filter { it.second >= DUST_LIMIT_SATS }
    }

    private fun checkLedger(escrowId: String, request: PayoutRequest, inSats: Long, outSats: Long) {
        val state = indexer.state(escrowId)
        val paying = request.recipients.sumOf { it.second }
        if (paying > state.reservedSats) throw PolicyViolation("payout exceeds reserved funds")
        if (indexer.payoutTxid(escrowId, OpReturn.payoutIdHash(request.payoutId)) != null) {
            throw PolicyViolation("payout id already confirmed")
        }
        val fee = inSats - outSats
        if (fee <= 0) throw PolicyViolation("non-positive mining fee")
        if (fee > state.remainingSats + state.feeReservationSats) {
            throw PolicyViolation("mining fee exceeds the launcher's share")
        }
    }

    private fun identity(xonly: String): String =
        org.hpb.engine.Descriptors.genesis(xonly, network).address
}
