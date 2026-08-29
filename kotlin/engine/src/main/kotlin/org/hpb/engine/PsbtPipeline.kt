package org.hpb.engine

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val SATS_PER_BTC = 100_000_000L

/** nSequence that keeps a transaction CLTV-eligible. */
const val CLTV_SEQUENCE = 0xFFFFFFFEL

fun satsToBtc(sats: Long): String =
    BigDecimal(sats).movePointLeft(8).toPlainString()

fun btcToSats(btc: String): Long =
    BigDecimal(btc).movePointRight(8).longValueExact()

data class TxInput(val txid: String, val vout: Int, val sequence: Long? = null)

data class TxOutputs(
    val payments: List<Pair<String, Long>> = emptyList(),
    val opReturn: ByteArray? = null,
)

/**
 * The multi-party PSBT pipeline, driven by Bitcoin Core: each signing party
 * calls [sign] with a descriptor carrying ONLY its own private key, so the
 * 2-of-3 flow is a real PSBT handoff even inside one process. Core picks the
 * cheapest satisfiable leaf at finalization.
 */
class PsbtPipeline(private val rpc: Rpc) {

    fun create(inputs: List<TxInput>, outputs: TxOutputs, locktime: Int = 0): String {
        val ins = JsonArray(
            inputs.map { input ->
                val fields = buildMap {
                    put("txid", JsonPrimitive(input.txid))
                    put("vout", JsonPrimitive(input.vout))
                    input.sequence?.let { put("sequence", JsonPrimitive(it)) }
                }
                JsonObject(fields)
            },
        )
        val outs = ArrayList<JsonObject>()
        outputs.payments.forEach { (address, sats) ->
            outs.add(JsonObject(mapOf(address to JsonPrimitive(satsToBtc(sats)))))
        }
        outputs.opReturn?.let { outs.add(JsonObject(mapOf("data" to JsonPrimitive(it.hex())))) }
        return rpc.call("createpsbt", ins, JsonArray(outs), JsonPrimitive(locktime))
            .jsonPrimitive.content
    }

    /** Fill witness UTXOs + taproot fields from the UTXO set and descriptors. */
    fun updateUtxos(psbt: String, descriptors: List<String>): String {
        val descs = JsonArray(
            descriptors.map {
                JsonObject(
                    mapOf(
                        "desc" to JsonPrimitive(rpc.descriptorWithChecksum(it)),
                        "range" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))),
                    ),
                )
            },
        )
        return rpc.call("utxoupdatepsbt", JsonPrimitive(psbt), descs).jsonPrimitive.content
    }

    /** One party signs with a descriptor holding only its own private key. */
    fun sign(psbt: String, privateDescriptor: String): String {
        val result = rpc.call(
            "descriptorprocesspsbt",
            JsonPrimitive(psbt),
            JsonArray(listOf(JsonPrimitive(rpc.descriptorWithChecksum(privateDescriptor)))),
            JsonPrimitive("DEFAULT"),
            JsonPrimitive(true),
            JsonPrimitive(false),
        ).jsonObject
        return result.getValue("psbt").jsonPrimitive.content
    }

    fun combine(psbts: List<String>): String {
        if (psbts.size == 1) return psbts.single()
        return rpc.call("combinepsbt", JsonArray(psbts.map { JsonPrimitive(it) }))
            .jsonPrimitive.content
    }

    /** Finalize to raw hex; throws if not yet satisfiable. */
    fun finalize(psbt: String): String {
        val result = rpc.call("finalizepsbt", JsonPrimitive(psbt), JsonPrimitive(true)).jsonObject
        check(result.getValue("complete").jsonPrimitive.boolean) {
            "PSBT not complete: missing signatures or unsatisfiable"
        }
        return result.getValue("hex").jsonPrimitive.content
    }

    /** testmempoolaccept then send; returns txid. */
    fun broadcast(txHex: String): String {
        val (allowed, reason) = mempoolCheck(txHex)
        check(allowed) { "transaction rejected by mempool: $reason" }
        return rpc.call("sendrawtransaction", JsonPrimitive(txHex)).jsonPrimitive.content
    }

    /** Finalize-and-probe without broadcasting: (allowed, rejectReason). */
    fun testAccept(psbt: String): Pair<Boolean, String?> {
        val result = rpc.call("finalizepsbt", JsonPrimitive(psbt), JsonPrimitive(true)).jsonObject
        if (!result.getValue("complete").jsonPrimitive.boolean) return false to "psbt-incomplete"
        return mempoolCheck(result.getValue("hex").jsonPrimitive.content)
    }

    private fun mempoolCheck(txHex: String): Pair<Boolean, String?> {
        val check = (
            rpc.call("testmempoolaccept", JsonArray(listOf(JsonPrimitive(txHex)))) as JsonArray
            )[0].jsonObject
        val allowed = check.getValue("allowed").jsonPrimitive.boolean
        return allowed to check["reject-reason"]?.jsonPrimitive?.content
    }
}
