package org.hpb.engine.index

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.hpb.engine.OpReturn
import org.hpb.engine.Rpc
import org.hpb.engine.Script
import org.hpb.engine.btcToSats
import org.hpb.engine.hex
import org.hpb.engine.hexBytes

/**
 * Walks the participant's own bitcoind block by block, recording protocol
 * observations for watched scripts and HMTB tags. Keeps a rolling window of
 * block hashes and rolls back derived rows on reorgs.
 */
internal class Scanner(private val db: IndexDb, private val rpc: Rpc) {
    private companion object {
        const val HASH_WINDOW = 100
    }

    private val tags = TagEffects(db)

    fun sync() {
        rollbackReorg()
        val tip = rpc.call("getblockcount").jsonPrimitive.int
        val last = db.queryLong("SELECT COALESCE(MAX(height), -1) FROM blocks").toInt()
        // With no history yet: scan from genesis if escrows are registered, else
        // baseline at the current tip (processing it) so later blocks are never skipped.
        val fresh = if (db.queryLong("SELECT COUNT(*) FROM escrows") > 0) 0 else tip
        val start = if (last < 0) fresh else last + 1
        for (height in start..tip) processBlock(height)
    }

    private fun rollbackReorg() {
        val stored = db.query(
            "SELECT height, hash FROM blocks ORDER BY height DESC LIMIT $HASH_WINDOW",
        ) { it.getInt(1) to it.getString(2) }
        for ((height, hash) in stored) {
            if (chainHashAt(height) == hash) return rollbackTo(height + 1)
        }
        if (stored.isNotEmpty()) rollbackTo(stored.last().first)
    }

    /** null when the height is beyond the (possibly reorged-down) tip. */
    private fun chainHashAt(height: Int): String? = try {
        rpc.call("getblockhash", JsonPrimitive(height)).jsonPrimitive.content
    } catch (_: org.hpb.engine.RpcException) {
        null
    }

    private fun rollbackTo(height: Int) {
        db.exec("DELETE FROM blocks WHERE height>=?", height)
        db.exec("DELETE FROM txs WHERE height>=?", height)
        db.exec("DELETE FROM utxos WHERE height>=?", height)
        db.exec("DELETE FROM payout_outputs WHERE height>=?", height)
        db.exec("DELETE FROM stakes WHERE height>=?", height)
        db.exec(
            "UPDATE utxos SET spent_txid=NULL, spent_height=NULL, spent_leaf=NULL " +
                "WHERE spent_height>=?",
            height,
        )
        db.exec("UPDATE stakes SET spent_txid=NULL WHERE spent_txid NOT IN (SELECT txid FROM txs)")
        db.exec("UPDATE escrows SET setup_txid=NULL WHERE setup_txid NOT IN (SELECT txid FROM txs)")
    }

    private fun processBlock(height: Int) {
        val hash = rpc.call("getblockhash", JsonPrimitive(height)).jsonPrimitive.content
        val block = rpc.call("getblock", JsonPrimitive(hash), JsonPrimitive(2)).jsonObject
        val time = block.getValue("time").jsonPrimitive.long
        val watched = watchedScripts()
        for (tx in block.getValue("tx").jsonArray) {
            processTx(tx.jsonObject, height, time, watched)
        }
        db.exec("INSERT OR REPLACE INTO blocks(height, hash) VALUES (?,?)", height, hash)
        db.exec("DELETE FROM blocks WHERE height<?", height - HASH_WINDOW)
    }

    /** scriptPubKey hex -> (escrowId, kind). */
    private fun watchedScripts(): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        db.query("SELECT escrow_id, genesis_script, vault_script FROM escrows") { rs ->
            Triple(rs.getString(1), rs.getString(2), rs.getString(3))
        }.forEach { (id, genesis, vault) ->
            out[genesis] = id to "genesis"
            vault?.let { out[it] = id to "vault" }
        }
        return out
    }

    private data class TxContext(val txid: String, val height: Int, val time: Long)

    private fun processTx(
        tx: JsonObject,
        height: Int,
        time: Long,
        watched: Map<String, Pair<String, String>>,
    ) {
        val txid = tx.getValue("txid").jsonPrimitive.content
        val spends = markSpends(tx, txid, height)
        val creates = recordCreates(tx, txid, height, watched, spentEscrows = spends.keys)
        val tag = corroboratedTag(tags.findTag(tx), spends)
        if (tag != null || spends.isNotEmpty() || creates.isNotEmpty()) {
            recordTx(tx, TxContext(txid, height, time), tag, spends, creates)
        }
    }

    /** A tag naming an escrow counts only when this tx SPENDS that escrow's
     *  watched UTXOs — anyone can broadcast an OP_RETURN naming any escrow,
     *  and anyone can send dust TO its addresses, so neither a bare tag nor
     *  a deposit corroborates a state transition. Unknown records
     *  (unrecognized or malformed types) have no defined effects and are
     *  dropped here. */
    private fun corroboratedTag(
        rawTag: OpReturn.Record?,
        spends: Map<String, String>,
    ): OpReturn.Record? = rawTag
        ?.takeUnless { it is OpReturn.Unknown }
        ?.takeIf { tag -> tags.tagEscrowId(tag)?.let { it in spends.keys } ?: true }

    private fun recordTx(
        tx: JsonObject,
        ctx: TxContext,
        tag: OpReturn.Record?,
        spends: Map<String, String>,
        creates: List<String>,
    ) {
        val escrowId =
            tag?.let(tags::tagEscrowId) ?: spends.keys.firstOrNull() ?: creates.firstOrNull()
        db.exec(
            "INSERT OR REPLACE INTO txs(txid, height, block_time, escrow_id, tag_hex, " +
                "spends_vault) VALUES (?,?,?,?,?,?)",
            ctx.txid, ctx.height, ctx.time, escrowId,
            tag?.let { OpReturn.encode(it).hex() },
            if (spends.containsValue("vault")) 1 else 0,
        )
        tags.apply(tx, ctx.txid, ctx.height, tag, escrowId, spends)
    }

    /** Mark our UTXOs spent by this tx; returns escrowId -> kind for spent rows. */
    private fun markSpends(tx: JsonObject, txid: String, height: Int): Map<String, String> {
        val out = HashMap<String, String>()
        tx.getValue("vin").jsonArray.forEachIndexed { index, vin ->
            val prev = vin.jsonObject["txid"]?.jsonPrimitive?.content ?: return@forEachIndexed
            val prevVout = vin.jsonObject.getValue("vout").jsonPrimitive.int
            val row = db.query(
                "SELECT escrow_id, kind FROM utxos WHERE txid=? AND vout=?",
                prev, prevVout,
            ) { it.getString(1) to it.getString(2) }.firstOrNull() ?: return@forEachIndexed
            db.exec(
                "UPDATE utxos SET spent_txid=?, spent_height=?, spent_leaf=? WHERE txid=? AND vout=?",
                txid, height, witnessLeaf(tx, index), prev, prevVout,
            )
            db.exec("UPDATE stakes SET spent_txid=? WHERE txid=? AND vout=?", txid, prev, prevVout)
            row.first?.let { out[it] = row.second }
        }
        return out
    }

    /** Record new UTXOs at watched scripts; returns the escrow ids credited. */
    private fun recordCreates(
        tx: JsonObject,
        txid: String,
        height: Int,
        watched: Map<String, Pair<String, String>>,
        spentEscrows: Set<String>,
    ): List<String> {
        val credited = ArrayList<String>()
        for (out in tx.getValue("vout").jsonArray) {
            val obj = out.jsonObject
            val spk = obj.getValue("scriptPubKey").jsonObject
            val (escrowId, kind) = watched[spk.getValue("hex").jsonPrimitive.content] ?: continue
            db.exec(
                "INSERT OR REPLACE INTO utxos(txid, vout, address, sats, height, escrow_id, " +
                    "kind, is_deposit) VALUES (?,?,?,?,?,?,?,?)",
                txid, obj.getValue("n").jsonPrimitive.int,
                spk["address"]?.jsonPrimitive?.content ?: "",
                btcToSats(obj.getValue("value").jsonPrimitive.content),
                height, escrowId, kind, if (escrowId in spentEscrows) 0 else 1,
            )
            credited.add(escrowId)
        }
        return credited
    }
}

/** Tapleaf hash hex for a script-path spend at input index, else null. */
private fun witnessLeaf(tx: JsonObject, index: Int): String? {
    val witness = tx.getValue("vin").jsonArray[index].jsonObject["txinwitness"]
        ?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
    if (witness.size < 2) return null
    val control = witness.last().hexBytes()
    if (control.isEmpty() || (control[0].toInt() and 0xfe) != Script.LEAF_VERSION) return null
    return Script.tapleafHash(witness[witness.size - 2].hexBytes()).hex()
}
