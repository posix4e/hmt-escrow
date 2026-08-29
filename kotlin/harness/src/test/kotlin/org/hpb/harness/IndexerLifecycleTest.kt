package org.hpb.harness

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.hpb.engine.Descriptors
import org.hpb.engine.EscrowStatus
import org.hpb.engine.Keys
import org.hpb.engine.Network
import org.hpb.engine.OpReturn
import org.hpb.engine.PsbtPipeline
import org.hpb.engine.Secp
import org.hpb.engine.TxInput
import org.hpb.engine.TxOutputs
import org.hpb.engine.Vault
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.sha256
import org.hpb.engine.wif
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * K2 proof: the embedded chain reader derives the full escrow lifecycle from
 * real regtest transactions, and converges through a reorg.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IndexerLifecycleTest {
    private val node = RegtestNode.start()
    private val pipeline = PsbtPipeline(node.rpc)
    private val db = IndexDb(Files.createTempFile("hpb-index", ".sqlite").toString())
    private val indexer = Indexer(db, node.rpc)

    private val launcherKey = ByteArray(32).also { it[30] = 0x20; it[31] = 0x01 }
    private val witness1Key = ByteArray(32).also { it[30] = 0x20; it[31] = 0x02 }
    private val witness2Key = ByteArray(32).also { it[30] = 0x20; it[31] = 0x03 }

    private val genesisKey = Keys.genesisPrivateKey(launcherKey, "job-1", 0, Network.REGTEST)
    private val genesis = Descriptors.genesis(Secp.xonlyHex(genesisKey), Network.REGTEST)
    private val manifestHash = sha256("manifest".toByteArray())
    private val fundSats = 1_000_000L
    private val feeSats = 500L

    private lateinit var escrowId: String
    private lateinit var vault: Vault
    private lateinit var finalPayoutBlock: String

    @AfterAll
    fun tearDown() {
        node.close()
        db.close()
    }

    private fun escrowIdBytes(): ByteArray = Keys.escrowId(genesis.scriptPubKey)

    private fun status(): EscrowStatus = indexer.state(escrowId).status

    @Test
    @Order(1)
    fun launchedThenFunded() {
        escrowId = indexer.registerEscrow(genesis, Secp.xonlyHex(launcherKey), "job-1", 1000)
        assertEquals(EscrowStatus.LAUNCHED, status())

        node.fund(genesis.address, fundSats)
        val state = indexer.state(escrowId)
        assertEquals(EscrowStatus.LAUNCHED, state.status)
        assertEquals(fundSats, state.balanceSats)
        assertEquals(fundSats, state.totalFundedSats)
    }

    @Test
    @Order(2)
    fun setupMovesToPending() {
        vault = Descriptors.vault(
            Secp.xonlyHex(launcherKey), Secp.xonlyHex(witness1Key), Secp.xonlyHex(witness2Key),
            20, node.height() + 200, Network.REGTEST,
        )
        indexer.registerVault(escrowId, vault)

        val genesisUtxo = node.fundingUtxo(genesis.address)
        val setupTag = OpReturn.encode(OpReturn.Setup(escrowIdBytes(), manifestHash, 5, 3))
        val unsigned = pipeline.updateUtxos(
            pipeline.create(
                listOf(TxInput(genesisUtxo.first, genesisUtxo.second)),
                TxOutputs(
                    payments = listOf(vault.address to fundSats - feeSats),
                    opReturn = setupTag,
                ),
            ),
            listOf(genesis.descriptor),
        )
        val signed = pipeline.sign(unsigned, "tr(${wif(genesisKey, Network.REGTEST)})")
        pipeline.broadcast(pipeline.finalize(signed))
        node.mine(1)

        val state = indexer.state(escrowId)
        assertEquals(EscrowStatus.PENDING, state.status)
        assertEquals(fundSats - feeSats, state.balanceSats)
        assertEquals(fundSats, state.totalFundedSats)
        // fee reservation = 8% of total funded
        assertEquals(fundSats * 8 / 100, state.feeReservationSats)
    }

    @Test
    @Order(3)
    fun reservationLedger() {
        val over = indexer.ingestReservation(
            Indexer.Reservation("ev-over", escrowId, Secp.xonlyHex(witness2Key), 10_000_000, 1, 2000),
            verified = true,
        )
        assertFalse(over, "over-remaining reservation must be rejected")

        val ok = indexer.ingestReservation(
            Indexer.Reservation("ev-1", escrowId, Secp.xonlyHex(witness2Key), 300_000, 1, 2001),
            verified = true,
        )
        assertTrue(ok)
        val stale = indexer.ingestReservation(
            Indexer.Reservation("ev-stale", escrowId, Secp.xonlyHex(witness2Key), 1_000, 1, 2002),
            verified = true,
        )
        assertFalse(stale, "non-increasing seq must be rejected")

        assertEquals(300_000, indexer.state(escrowId).reservedSats)
    }

    private fun vaultSpend(
        inputs: List<TxInput>,
        outputs: TxOutputs,
    ): String {
        val unsigned = pipeline.updateUtxos(pipeline.create(inputs, outputs), listOf(vault.descriptor))
        val combined = pipeline.combine(
            listOf(
                pipeline.sign(
                    unsigned,
                    vault.descriptorWithPrivateKey(
                        Secp.xonlyHex(launcherKey), wif(launcherKey, Network.REGTEST),
                    ),
                ),
                pipeline.sign(
                    unsigned,
                    vault.descriptorWithPrivateKey(
                        Secp.xonlyHex(witness1Key), wif(witness1Key, Network.REGTEST),
                    ),
                ),
            ),
        )
        return pipeline.broadcast(pipeline.finalize(combined))
    }

    @Test
    @Order(4)
    fun partialPayout() {
        val vaultUtxo = node.fundingUtxo(vault.address)
        val balance = indexer.state(escrowId).balanceSats
        val tag = OpReturn.encode(
            OpReturn.Payout(
                OpReturn.payoutIdHash("batch-1"), sha256("results".toByteArray()),
                forceComplete = false, finalized = false,
            ),
        )
        vaultSpend(
            listOf(TxInput(vaultUtxo.first, vaultUtxo.second)),
            TxOutputs(
                payments = listOf(
                    node.newAddress() to 100_000L,
                    node.newAddress() to 150_000L,
                    vault.address to balance - 250_000L - feeSats,
                ),
                opReturn = tag,
            ),
        )
        node.mine(1)

        val state = indexer.state(escrowId)
        assertEquals(EscrowStatus.PARTIAL, state.status)
        assertEquals(250_000L, state.amountPaidSats)
        assertEquals(50_000L, state.reservedSats)
        assertEquals(balance - 250_000L - feeSats, state.balanceSats)
    }

    @Test
    @Order(5)
    fun finalizedPayoutCompletesAndSurvivesReorg() {
        val changeUtxo = node.fundingUtxo(vault.address)
        val balance = indexer.state(escrowId).balanceSats
        val tag = OpReturn.encode(
            OpReturn.Payout(
                OpReturn.payoutIdHash("batch-2"), sha256("final".toByteArray()),
                forceComplete = true, finalized = true,
            ),
        )
        vaultSpend(
            listOf(TxInput(changeUtxo.first, changeUtxo.second)),
            TxOutputs(
                payments = listOf(node.newAddress() to balance - feeSats),
                opReturn = tag,
            ),
        )
        finalPayoutBlock = node.mine(1).first()
        assertEquals(EscrowStatus.COMPLETE, status())
        assertEquals(0L, indexer.state(escrowId).balanceSats)

        node.rpc.call("invalidateblock", JsonPrimitive(finalPayoutBlock))
        assertEquals(EscrowStatus.PARTIAL, status(), "reorg rolls the finalize back")

        node.rpc.call("reconsiderblock", JsonPrimitive(finalPayoutBlock))
        assertEquals(EscrowStatus.COMPLETE, status())
    }

    @Test
    @Order(6)
    fun genesisSweepCancels() {
        val cancelKey = Keys.genesisPrivateKey(launcherKey, "job-cancel", 0, Network.REGTEST)
        val cancelGenesis = Descriptors.genesis(Secp.xonlyHex(cancelKey), Network.REGTEST)
        val cancelId =
            indexer.registerEscrow(cancelGenesis, Secp.xonlyHex(launcherKey), "job-cancel", 3000)
        val (txid, vout) = node.fund(cancelGenesis.address, fundSats)

        val tag = OpReturn.encode(OpReturn.Cancel(Keys.escrowId(cancelGenesis.scriptPubKey)))
        val unsigned = pipeline.updateUtxos(
            pipeline.create(
                listOf(TxInput(txid, vout)),
                TxOutputs(
                    payments = listOf(node.newAddress() to fundSats - feeSats),
                    opReturn = tag,
                ),
            ),
            listOf(cancelGenesis.descriptor),
        )
        val signed = pipeline.sign(unsigned, "tr(${wif(cancelKey, Network.REGTEST)})")
        pipeline.broadcast(pipeline.finalize(signed))
        node.mine(1)

        assertEquals(EscrowStatus.CANCELLED, indexer.state(cancelId).status)
    }
}
