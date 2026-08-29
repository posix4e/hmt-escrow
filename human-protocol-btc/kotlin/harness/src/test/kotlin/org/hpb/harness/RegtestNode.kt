package org.hpb.harness

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.Rpc
import org.hpb.engine.btcToSats
import org.hpb.engine.satsToBtc

/** A throwaway bitcoind -regtest with a funded miner wallet. */
class RegtestNode private constructor(
    val rpc: Rpc,
    val miner: Rpc,
    private val process: Process,
    val datadir: Path,
) : AutoCloseable {

    companion object {
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        fun start(): RegtestNode {
            val exe = System.getenv("BITCOIND_EXE")?.takeIf { it.isNotBlank() } ?: "bitcoind"
            val datadir = Files.createTempDirectory("hpb-regtest")
            val rpcPort = freePort()
            val process = ProcessBuilder(
                exe, "-regtest", "-datadir=$datadir", "-port=${freePort()}",
                "-rpcport=$rpcPort", "-txindex", "-fallbackfee=0.0001", "-listen=0", "-server=1",
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val rpc = Rpc.withCookie("http://127.0.0.1:$rpcPort", datadir.resolve("regtest/.cookie"))
            rpc.waitReady()
            rpc.assertVersion()
            rpc.call("createwallet", JsonPrimitive("miner"))
            val node = RegtestNode(rpc, rpc.wallet("miner"), process, datadir)
            node.mine(101)
            return node
        }
    }

    fun mine(blocks: Int): List<String> {
        val address = miner.call("getnewaddress").jsonPrimitive.content
        return miner.call("generatetoaddress", JsonPrimitive(blocks), JsonPrimitive(address))
            .jsonArray.map { it.jsonPrimitive.content }
    }

    fun height(): Int = rpc.call("getblockcount").jsonPrimitive.int

    fun newAddress(): String = miner.call("getnewaddress").jsonPrimitive.content

    /** Send sats to an address and confirm; returns (txid, vout). */
    fun fund(address: String, sats: Long): Pair<String, Int> {
        val txid = miner.call(
            "sendtoaddress", JsonPrimitive(address), JsonPrimitive(satsToBtc(sats)),
        ).jsonPrimitive.content
        mine(1)
        return txid to findVout(txid, address)
    }

    fun findVout(txid: String, address: String): Int {
        val tx = rpc.call("getrawtransaction", JsonPrimitive(txid), JsonPrimitive(true)).jsonObject
        for (out in tx.getValue("vout").jsonArray) {
            val obj = out.jsonObject
            val outAddress =
                obj.getValue("scriptPubKey").jsonObject["address"]?.jsonPrimitive?.content
            if (outAddress == address) return obj.getValue("n").jsonPrimitive.int
        }
        error("output paying $address not found in $txid")
    }

    /** The single unspent UTXO at an address: (txid, vout). */
    fun fundingUtxo(address: String): Pair<String, Int> {
        val scan = rpc.call(
            "scantxoutset",
            JsonPrimitive("start"),
            JsonArray(listOf(JsonPrimitive("addr($address)"))),
        ).jsonObject
        val unspent = scan.getValue("unspents").jsonArray.single().jsonObject
        return unspent.getValue("txid").jsonPrimitive.content to
            unspent.getValue("vout").jsonPrimitive.int
    }

    /** Confirmed balance of an address in sats (scantxoutset). */
    fun addressBalance(address: String): Long {
        val scan = rpc.call(
            "scantxoutset",
            JsonPrimitive("start"),
            JsonArray(listOf(JsonPrimitive("addr($address)"))),
        ).jsonObject
        check(scan.getValue("success").jsonPrimitive.boolean)
        return btcToSats(scan.getValue("total_amount").jsonPrimitive.content)
    }

    /** Witness items of a confirmed tx's input 0 (for tapleaf classification). */
    fun witness(txid: String): List<String> {
        val tx = rpc.call("getrawtransaction", JsonPrimitive(txid), JsonPrimitive(true)).jsonObject
        return tx.getValue("vin").jsonArray[0].jsonObject
            .getValue("txinwitness").jsonArray.map { it.jsonPrimitive.content }
    }

    override fun close() {
        process.destroy()
        process.waitFor()
    }
}
