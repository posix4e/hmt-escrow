package org.hpb.harness

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hpb.engine.CLTV_SEQUENCE
import org.hpb.engine.Descriptors
import org.hpb.engine.Network
import org.hpb.engine.PsbtPipeline
import org.hpb.engine.Script
import org.hpb.engine.Secp
import org.hpb.engine.TxInput
import org.hpb.engine.TxOutputs
import org.hpb.engine.Vault
import org.hpb.engine.VaultLeaf
import org.hpb.engine.hexBytes
import org.hpb.engine.wif
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Milestone K1 proof, ported from the Python M1 suite: every vault spend path
 * validates on a real regtest node, and witnesses classify by tapleaf.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VaultSpendTest {
    private val node = RegtestNode.start()
    private val pipeline = PsbtPipeline(node.rpc)

    private val launcherKey = ByteArray(32).also { it[30] = 0x10; it[31] = 0x01 }
    private val witness1Key = ByteArray(32).also { it[30] = 0x10; it[31] = 0x02 }
    private val witness2Key = ByteArray(32).also { it[30] = 0x10; it[31] = 0x03 }

    private val fundSats = 1_000_000L
    private val feeSats = 500L
    private val cancelDelay = 20

    @AfterAll
    fun tearDown() = node.close()

    private fun newVault(expiryDelta: Int = 200): Triple<Vault, String, Int> {
        val vault = Descriptors.vault(
            Secp.xonlyHex(launcherKey),
            Secp.xonlyHex(witness1Key),
            Secp.xonlyHex(witness2Key),
            cancelDelay,
            node.height() + expiryDelta,
            Network.REGTEST,
        )
        assertEquals(vault.address, node.rpc.deriveAddress(vault.descriptor), "Core parity")
        val (txid, vout) = node.fund(vault.address, fundSats)
        return Triple(vault, txid, vout)
    }

    private fun signedBy(psbt: String, vault: Vault, key: ByteArray): String {
        val descriptor =
            vault.descriptorWithPrivateKey(Secp.xonlyHex(key), wif(key, Network.REGTEST))
        return pipeline.sign(psbt, descriptor)
    }

    private fun spentLeaf(txid: String, vault: Vault): VaultLeaf {
        val witness = node.witness(txid)
        val script = witness[witness.size - 2].hexBytes()
        val controlBlock = witness.last().hexBytes()
        assertEquals(org.hpb.engine.Keys.NUMS_XONLY, controlBlock.copyOfRange(1, 33).let {
            it.joinToString("") { b -> "%02x".format(b) }
        })
        return vault.leafOf(Script.tapleafHash(script)) ?: error("unknown tapscript in witness")
    }

    @Test
    fun cooperativeMultiASpend() {
        val (vault, txid, vout) = newVault()
        val unsigned = pipeline.updateUtxos(
            pipeline.create(
                listOf(TxInput(txid, vout)),
                TxOutputs(payments = listOf(node.newAddress() to fundSats - feeSats)),
            ),
            listOf(vault.descriptor),
        )
        val combined = pipeline.combine(
            listOf(signedBy(unsigned, vault, launcherKey), signedBy(unsigned, vault, witness1Key)),
        )
        val spendTxid = pipeline.broadcast(pipeline.finalize(combined))
        node.mine(1)
        assertEquals(VaultLeaf.PAYOUT, spentLeaf(spendTxid, vault))
    }

    @Test
    fun oneSignatureIsNotEnough() {
        val (vault, txid, vout) = newVault()
        val unsigned = pipeline.updateUtxos(
            pipeline.create(
                listOf(TxInput(txid, vout)),
                TxOutputs(payments = listOf(node.newAddress() to fundSats - feeSats)),
            ),
            listOf(vault.descriptor),
        )
        val once = signedBy(unsigned, vault, witness1Key)
        assertFailsWith<IllegalStateException> { pipeline.finalize(once) }
    }

    @Test
    fun csvRefundLeaf() {
        val (vault, txid, vout) = newVault()
        val dest = node.newAddress()

        fun buildSigned(): String {
            val unsigned = pipeline.updateUtxos(
                pipeline.create(
                    listOf(TxInput(txid, vout, sequence = cancelDelay.toLong())),
                    TxOutputs(payments = listOf(dest to fundSats - feeSats)),
                ),
                listOf(vault.descriptor),
            )
            return signedBy(unsigned, vault, launcherKey)
        }

        val early = pipeline.testAccept(buildSigned())
        assertFalse(early.first)
        assertTrue(early.second!!.contains("non-BIP68-final"))

        node.mine(cancelDelay)
        val spendTxid = pipeline.broadcast(pipeline.finalize(buildSigned()))
        node.mine(1)
        assertEquals(VaultLeaf.REFUND, spentLeaf(spendTxid, vault))
    }

    @Test
    fun cltvSweepLeaf() {
        val (vault, txid, vout) = newVault(expiryDelta = 5)
        val dest = node.newAddress()

        fun buildSigned(): String {
            val unsigned = pipeline.updateUtxos(
                pipeline.create(
                    listOf(TxInput(txid, vout, sequence = CLTV_SEQUENCE)),
                    TxOutputs(payments = listOf(dest to fundSats - feeSats)),
                    locktime = vault.expiryHeight,
                ),
                listOf(vault.descriptor),
            )
            return signedBy(unsigned, vault, witness1Key)
        }

        val early = pipeline.testAccept(buildSigned())
        assertFalse(early.first)
        assertTrue(early.second!!.contains("non-final"))

        while (node.height() < vault.expiryHeight) node.mine(1)
        val spendTxid = pipeline.broadcast(pipeline.finalize(buildSigned()))
        node.mine(1)
        assertEquals(VaultLeaf.SWEEP, spentLeaf(spendTxid, vault))
    }
}
