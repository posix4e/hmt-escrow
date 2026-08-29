package org.hpb.engine.escrow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.CLTV_SEQUENCE
import org.hpb.engine.Descriptors
import org.hpb.engine.Network
import org.hpb.engine.OpReturn
import org.hpb.engine.PsbtPipeline
import org.hpb.engine.Rpc
import org.hpb.engine.TxInput
import org.hpb.engine.TxOutputs
import org.hpb.engine.hex
import org.hpb.engine.hexBytes
import org.hpb.engine.index.Indexer
import org.hpb.engine.satsToBtc

/**
 * Staking as CLTV time-locked bond UTXOs: provable, NON-slashable (Bitcoin
 * has no covenant to enforce slashing — an explicit, documented limitation).
 * Stakes gate escrow creation; unstaking marks bonds pending, and withdrawal
 * is consensus-enforced by the CLTV leaf after maturity.
 */
class Staking(
    private val network: Network,
    private val rpc: Rpc,
    private val indexer: Indexer,
) {
    private val pipeline = PsbtPipeline(rpc)

    /** Lock sats into a bond funded and signed by the given Core wallet. */
    fun stake(stakerXonly: String, sats: Long, walletRpc: Rpc): String {
        val unlockHeight = currentHeight() + network.stakeLockBlocks
        val bond = Descriptors.bond(stakerXonly, unlockHeight, network)
        val tag = OpReturn.encode(
            OpReturn.Stake(stakerXonly.hexBytes(), unlockHeight.toLong()),
        )
        val outputs = JsonArray(
            listOf(
                JsonObject(mapOf(bond.address to JsonPrimitive(satsToBtc(sats)))),
                JsonObject(mapOf("data" to JsonPrimitive(tag.hex()))),
            ),
        )
        val funded = walletRpc.call(
            "walletcreatefundedpsbt", JsonArray(emptyList()), outputs,
        ).jsonObject.getValue("psbt").jsonPrimitive.content
        val signed = walletRpc.call("walletprocesspsbt", JsonPrimitive(funded))
            .jsonObject.getValue("psbt").jsonPrimitive.content
        return pipeline.broadcast(pipeline.finalize(signed))
    }

    fun available(stakerXonly: String): Long {
        indexer.sync()
        return indexer.availableStake(stakerXonly)
    }

    /** Bonds stop counting toward the stake immediately; funds stay timelocked. */
    fun unstake(stakerXonly: String) {
        indexer.sync()
        indexer.markUnstake(stakerXonly)
    }

    /** Spend matured, unstake-marked bonds back to the staker via the CLTV leaf. */
    fun buildWithdraw(stakerXonly: String, destinationAddress: String): String {
        indexer.sync()
        val bonds = indexer.withdrawableBonds(stakerXonly)
        require(bonds.isNotEmpty()) { "no unstake-marked bonds to withdraw" }
        val locktime = bonds.maxOf { it.unlockHeight }
        val fee = estimateFeeSats(bonds.size, 1)
        return pipeline.create(
            bonds.map { TxInput(it.txid, it.vout, sequence = CLTV_SEQUENCE) },
            TxOutputs(payments = listOf(destinationAddress to bonds.sumOf { it.sats } - fee)),
            locktime = locktime,
        )
    }

    /** The bond descriptor (with the staker's key slot) for signing withdrawals. */
    fun bondDescriptor(stakerXonly: String, unlockHeight: Int): Descriptors.Bond =
        Descriptors.bond(stakerXonly, unlockHeight, network)

    fun broadcast(signedPsbt: String): String = pipeline.broadcast(pipeline.finalize(signedPsbt))

    private fun currentHeight(): Int = rpc.call("getblockcount").jsonPrimitive.int
}
