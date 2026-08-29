package org.hpb.engine.index

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.Descriptors
import org.hpb.engine.Network
import org.hpb.engine.OpReturn
import org.hpb.engine.btcToSats
import org.hpb.engine.hex

/** Applies the side effects of HMTB-tagged transactions to the index tables. */
internal class TagEffects(private val db: IndexDb) {

    fun findTag(tx: JsonObject): OpReturn.Record? =
        tx.getValue("vout").jsonArray.firstNotNullOfOrNull { out ->
            val hex = out.jsonObject.getValue("scriptPubKey").jsonObject
                .getValue("hex").jsonPrimitive.content
            OpReturn.decodeScriptPubKey(hex)
        }

    fun tagEscrowId(tag: OpReturn.Record): String? = when (tag) {
        is OpReturn.Setup -> tag.escrowId.hex()
        is OpReturn.Cancel -> tag.escrowId.hex()
        is OpReturn.Complete -> tag.escrowId.hex()
        else -> null
    }

    fun apply(
        tx: JsonObject,
        txid: String,
        height: Int,
        tag: OpReturn.Record?,
        escrowId: String?,
        spends: Map<String, String>,
    ) {
        when (tag) {
            is OpReturn.Setup -> applySetupIfGenesisSpend(txid, tag, spends)
            is OpReturn.Payout -> applyPayoutIfVaultSpend(tx, txid, height, escrowId, spends)
            is OpReturn.Stake -> recordBond(tx, txid, height, tag)
            else -> {}
        }
    }

    private fun applySetupIfGenesisSpend(txid: String, tag: OpReturn.Setup, spends: Map<String, String>) {
        if (spends[tag.escrowId.hex()] == "genesis") applySetup(txid, tag)
    }

    private fun applyPayoutIfVaultSpend(
        tx: JsonObject,
        txid: String,
        height: Int,
        escrowId: String?,
        spends: Map<String, String>,
    ) {
        if (spends.containsValue("vault")) recordPayoutOutputs(tx, txid, height, escrowId)
    }

    /** SETUP binds to the unique genesis→vault transition and is immutable:
     *  only the tx spending the genesis UTXO may set it, exactly once (a
     *  reorg that drops that tx clears setup_txid, reopening the slot). */
    private fun applySetup(txid: String, tag: OpReturn.Setup) {
        db.exec(
            "UPDATE escrows SET setup_txid=?, fee1=?, fee2=?, manifest_hash=? " +
                "WHERE escrow_id=? AND setup_txid IS NULL",
            txid, tag.cosigner1FeePct, tag.cosigner2FeePct, tag.manifestHash.hex(),
            tag.escrowId.hex(),
        )
    }

    private fun recordPayoutOutputs(tx: JsonObject, txid: String, height: Int, escrowId: String?) {
        if (escrowId == null) return
        val vaultAddress = db.query(
            "SELECT vault_address FROM escrows WHERE escrow_id=?", escrowId,
        ) { it.getString(1) }.firstOrNull()
        tx.getValue("vout").jsonArray
            .map { it.jsonObject }
            .filter { addressOf(it) != null && addressOf(it) != vaultAddress }
            .forEach { out ->
                db.exec(
                    "INSERT OR REPLACE INTO payout_outputs(txid, vout, escrow_id, recipient, " +
                        "sats, height) VALUES (?,?,?,?,?,?)",
                    txid, out.getValue("n").jsonPrimitive.int, escrowId, addressOf(out),
                    btcToSats(out.getValue("value").jsonPrimitive.content), height,
                )
            }
    }

    private fun recordBond(tx: JsonObject, txid: String, height: Int, stake: OpReturn.Stake) {
        // Bond scriptPubKey depends only on (staker, unlockHeight); the network
        // parameter affects the address string, not the script.
        val bondScript = Descriptors.bond(
            stake.staker.hex(), stake.unlockHeight.toInt(), Network.REGTEST,
        ).scriptPubKey.hex()
        tx.getValue("vout").jsonArray.map { it.jsonObject }
            .filter { scriptHexOf(it) == bondScript }
            .forEach { out ->
                db.exec(
                    "INSERT OR REPLACE INTO stakes(txid, vout, staker, sats, unlock_height, " +
                        "height) VALUES (?,?,?,?,?,?)",
                    txid, out.getValue("n").jsonPrimitive.int, stake.staker.hex(),
                    btcToSats(out.getValue("value").jsonPrimitive.content),
                    stake.unlockHeight, height,
                )
            }
    }

    private fun addressOf(out: JsonObject): String? =
        out.getValue("scriptPubKey").jsonObject["address"]?.jsonPrimitive?.content

    private fun scriptHexOf(out: JsonObject): String =
        out.getValue("scriptPubKey").jsonObject.getValue("hex").jsonPrimitive.content
}
