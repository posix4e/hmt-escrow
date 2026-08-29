package org.hpb.harness

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.Descriptors
import org.hpb.engine.EscrowStatus
import org.hpb.engine.Keys
import org.hpb.engine.Network
import org.hpb.engine.OpReturn
import org.hpb.engine.PsbtPipeline
import org.hpb.engine.Secp
import org.hpb.engine.TxInput
import org.hpb.engine.TxOutputs
import org.hpb.engine.escrow.Escrows
import org.hpb.engine.hex
import org.hpb.engine.hexBytes
import org.hpb.engine.satsToBtc
import org.hpb.engine.escrow.PayoutRequest
import org.hpb.engine.escrow.PolicyViolation
import org.hpb.engine.escrow.PsbtSigner
import org.hpb.engine.escrow.SetupParams
import org.hpb.engine.escrow.Staking
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.index.Stats
import org.hpb.engine.sha256
import org.hpb.engine.wif
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * K3 proof: the escrow API drives the full lifecycle on regtest — staking
 * gate, setup, reservations, policy-checked co-signed payouts with fee and
 * refund outputs at finalize, idempotency, two-phase cancellation, withdraw.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EscrowApiTest {
    private val node = RegtestNode.start()
    private val pipeline = PsbtPipeline(node.rpc)
    private val db = IndexDb(Files.createTempFile("hpb-api", ".sqlite").toString())
    private val indexer = Indexer(db, node.rpc)
    private val escrows = Escrows(Network.REGTEST, node.rpc, indexer)
    private val staking = Staking(Network.REGTEST, node.rpc, indexer)

    private val launcherKey = ByteArray(32).also { it[30] = 0x30; it[31] = 0x01 }
    private val witness1Key = ByteArray(32).also { it[30] = 0x30; it[31] = 0x02 }
    private val witness2Key = ByteArray(32).also { it[30] = 0x30; it[31] = 0x03 }
    private val launcher = Secp.xonlyHex(launcherKey)
    private val witness1 = Secp.xonlyHex(witness1Key)
    private val witness2 = Secp.xonlyHex(witness2Key)

    private val fundSats = 1_000_000L
    private lateinit var escrowId: String

    @AfterAll
    fun tearDown() {
        node.close()
        db.close()
    }

    private fun genesisFor(jobId: String) = Keys.genesisPrivateKey(launcherKey, jobId, 0, Network.REGTEST).let {
        it to Descriptors.genesis(Secp.xonlyHex(it), Network.REGTEST)
    }

    private fun genesisSigner(genesisKey: ByteArray) =
        PsbtSigner { pipeline.sign(it, "tr(${wif(genesisKey, Network.REGTEST)})") }

    private fun vaultSigner(key: ByteArray) = PsbtSigner { psbt ->
        val vault = indexer.vaultOf(escrowId, Network.REGTEST)
        pipeline.sign(psbt, vault.descriptorWithPrivateKey(Secp.xonlyHex(key), wif(key, Network.REGTEST)))
    }

    private fun setupParams() = SetupParams(
        cosigner1 = witness1, cosigner2 = witness2,
        cosigner1FeePct = 5, cosigner2FeePct = 3,
        manifestHash = sha256("manifest".toByteArray()),
    )

    private fun newPendingEscrow(jobId: String): String {
        val (genesisKey, genesis) = genesisFor(jobId)
        val id = escrows.create(genesis, launcher, jobId)
        node.fund(genesis.address, fundSats)
        escrowId = id
        escrows.setup(id, setupParams(), genesisSigner(genesisKey))
        node.mine(1)
        return id
    }

    @Test
    @Order(1)
    fun stakingGatesEscrowCreation() {
        val (_, genesis) = genesisFor("job-gate")
        assertFailsWith<IllegalArgumentException> {
            escrows.create(genesis, launcher, "job-gate")
        }
        staking.stake(launcher, 200_000, node.miner)
        node.mine(1)
        assertEquals(200_000, staking.available(launcher))
    }

    @Test
    @Order(2)
    fun happyPathToComplete() {
        escrowId = newPendingEscrow("job-happy")
        assertEquals(EscrowStatus.PENDING, escrows.state(escrowId).status)

        assertTrue(escrows.reserve(escrowId, "r1", witness2, 300_000, 1))

        val worker1 = node.newAddress()
        val worker2 = node.newAddress()
        val partial = PayoutRequest(
            "batch-1", listOf(worker1 to 100_000L, worker2 to 150_000L),
            sha256("r".toByteArray()),
        )
        val psbt = assertNotNull(escrows.buildPayout(escrowId, partial))
        escrows.checkPayout(escrowId, psbt, partial) // witness policy passes
        escrows.broadcastPayout(
            listOf(vaultSigner(launcherKey).sign(psbt), vaultSigner(witness1Key).sign(psbt)),
        )
        node.mine(1)
        assertEquals(EscrowStatus.PARTIAL, escrows.state(escrowId).status)
        assertEquals(250_000L, escrows.state(escrowId).amountPaidSats)

        // idempotency: same payout id is a confirmed no-op
        assertNull(escrows.buildPayout(escrowId, partial))
        assertNotNull(escrows.payoutTxid(escrowId, "batch-1"))

        val worker3 = node.newAddress()
        val final = PayoutRequest(
            "batch-2", listOf(worker3 to 50_000L), sha256("f".toByteArray()), forceComplete = true,
        )
        val finalPsbt = assertNotNull(escrows.buildPayout(escrowId, final))
        escrows.checkPayout(escrowId, finalPsbt, final)
        escrows.broadcastPayout(
            listOf(vaultSigner(launcherKey).sign(finalPsbt), vaultSigner(witness1Key).sign(finalPsbt)),
        )
        node.mine(1)

        val state = escrows.state(escrowId)
        assertEquals(EscrowStatus.COMPLETE, state.status)
        assertEquals(0L, state.balanceSats)
        // co-signer fee outputs: 5% and 3% of total funded, at identity addresses
        assertEquals(50_000L, node.addressBalance(escrows.identityAddress(witness1)))
        assertEquals(30_000L, node.addressBalance(escrows.identityAddress(witness2)))
        assertTrue(node.addressBalance(escrows.identityAddress(launcher)) > 500_000L)
    }

    @Test
    @Order(3)
    fun payoutValidationRejectsBadRequests() {
        escrowId = newPendingEscrow("job-errors")
        assertTrue(escrows.reserve(escrowId, "r-err", witness2, 100_000, 1))

        val dust = PayoutRequest("d", listOf(node.newAddress() to 100L), ByteArray(32))
        assertFailsWith<IllegalArgumentException> { escrows.buildPayout(escrowId, dust) }

        val tooMany = PayoutRequest(
            "m", (1..101).map { node.newAddress() to 500L }, ByteArray(32),
        )
        assertFailsWith<IllegalArgumentException> { escrows.buildPayout(escrowId, tooMany) }

        val overReserved = PayoutRequest("o", listOf(node.newAddress() to 200_000L), ByteArray(32))
        assertFailsWith<IllegalArgumentException> { escrows.buildPayout(escrowId, overReserved) }
    }

    @Test
    @Order(4)
    fun policyRefusesTamperedPayouts() {
        // still on job-errors: build a legit PSBT, then check it against a
        // DIFFERENT claim than what it pays — the co-signer must refuse.
        val honest = PayoutRequest("t", listOf(node.newAddress() to 50_000L), ByteArray(32))
        val psbt = assertNotNull(escrows.buildPayout(escrowId, honest))
        val lie = PayoutRequest("t", listOf(node.newAddress() to 40_000L), ByteArray(32))
        assertFailsWith<PolicyViolation> { escrows.checkPayout(escrowId, psbt, lie) }
    }

    @Test
    @Order(4)
    fun policyRequiresThePayoutCommitment() {
        // correct recipient outputs but NO HMTB PAYOUT record — a confirmed
        // spend would leave the payout id unrecorded and the reservation
        // replayable, so the co-signer must refuse.
        val worker = node.newAddress()
        val request = PayoutRequest("c1", listOf(worker to 50_000L), ByteArray(32))
        val state = escrows.state(escrowId)
        val noTag = pipeline.create(
            indexer.unspentUtxos(escrowId, "vault").map { TxInput(it.txid, it.vout) },
            TxOutputs(
                payments = listOf(
                    worker to 50_000L,
                    indexer.vaultOf(escrowId, Network.REGTEST).address to state.balanceSats - 50_400L,
                ),
            ),
        )
        assertFailsWith<PolicyViolation> { escrows.checkPayout(escrowId, noTag, request) }
    }

    @Test
    @Order(4)
    fun policyKeepsUnfinalizedChangeInTheVault() {
        // mid-job "drain": worker paid exactly as claimed, but the change is
        // routed to the launcher instead of back to the vault.
        val worker = node.newAddress()
        val request = PayoutRequest("c2", listOf(worker to 50_000L), ByteArray(32))
        val tag = OpReturn.Payout(OpReturn.payoutIdHash("c2"), ByteArray(32), false, finalized = false)
        val drain = pipeline.create(
            indexer.unspentUtxos(escrowId, "vault").map { TxInput(it.txid, it.vout) },
            TxOutputs(
                payments = listOf(
                    worker to 50_000L,
                    escrows.identityAddress(launcher) to escrows.state(escrowId).balanceSats - 50_400L,
                ),
                opReturn = OpReturn.encode(tag),
            ),
        )
        assertFailsWith<PolicyViolation> { escrows.checkPayout(escrowId, drain, request) }
    }

    @Test
    @Order(4)
    fun policyEnforcesTheFeeSnapshotAtFinalize() {
        // finalizing payout that omits the SETUP-committed co-signer fees
        val worker = node.newAddress()
        val request = PayoutRequest("c3", listOf(worker to 50_000L), ByteArray(32), forceComplete = true)
        val tag = OpReturn.Payout(OpReturn.payoutIdHash("c3"), ByteArray(32), true, finalized = true)
        val greedy = pipeline.create(
            indexer.unspentUtxos(escrowId, "vault").map { TxInput(it.txid, it.vout) },
            TxOutputs(
                payments = listOf(
                    worker to 50_000L,
                    escrows.identityAddress(launcher) to escrows.state(escrowId).balanceSats - 50_400L,
                ),
                opReturn = OpReturn.encode(tag),
            ),
        )
        assertFailsWith<PolicyViolation> { escrows.checkPayout(escrowId, greedy, request) }
    }

    @Test
    @Order(4)
    fun scannerIgnoresForgedAndMalformedTags() {
        val before = escrows.state(escrowId).status
        val fees = indexer.feeSnapshot(escrowId)
        // anyone can broadcast an OP_RETURN naming any escrow — and anyone
        // can send dust TO its addresses. Neither a bare tag nor a
        // deposit-corroborated one may change state or overwrite the fee
        // snapshot, and malformed bytes must not wedge the scanner.
        broadcastDataTx(OpReturn.encode(OpReturn.Cancel(escrowId.hexBytes())))
        broadcastDataTx("HMTB".toByteArray() + byteArrayOf(0x01, 0x01, 0x00, 0x01))
        val vaultAddress = indexer.vaultOf(escrowId, Network.REGTEST).address
        broadcastDataTx(
            OpReturn.encode(OpReturn.Cancel(escrowId.hexBytes())),
            payTo = vaultAddress to 1_000L,
        )
        broadcastDataTx(
            OpReturn.encode(OpReturn.Setup(escrowId.hexBytes(), ByteArray(32), 99, 0)),
            payTo = vaultAddress to 1_000L,
        )
        node.mine(1)
        assertEquals(before, escrows.state(escrowId).status)
        assertEquals(fees, indexer.feeSnapshot(escrowId))
    }

    @Test
    @Order(4)
    fun policyRefusesSurplusOutputsAtFinalize() {
        // exact worker payment and exact fee for cosigner1, but cosigner2
        // takes the whole remainder on top of its fee instead of leaving the
        // launcher refund — the classic 2-of-3-without-the-launcher drain.
        val worker = node.newAddress()
        val request = PayoutRequest("c4", listOf(worker to 50_000L), ByteArray(32), forceComplete = true)
        val tag = OpReturn.Payout(OpReturn.payoutIdHash("c4"), ByteArray(32), true, finalized = true)
        val state = escrows.state(escrowId)
        val fee1 = state.totalFundedSats * 5 / 100
        val surplus = pipeline.create(
            indexer.unspentUtxos(escrowId, "vault").map { TxInput(it.txid, it.vout) },
            TxOutputs(
                payments = listOf(
                    worker to 50_000L,
                    escrows.identityAddress(witness1) to fee1,
                    escrows.identityAddress(witness2) to state.balanceSats - 50_000L - fee1 - 400L,
                ),
                opReturn = OpReturn.encode(tag),
            ),
        )
        assertFailsWith<PolicyViolation> { escrows.checkPayout(escrowId, surplus, request) }
    }

    private fun broadcastDataTx(payload: ByteArray, payTo: Pair<String, Long>? = null) {
        val outs = mutableListOf<JsonObject>()
        payTo?.let { outs += JsonObject(mapOf(it.first to JsonPrimitive(satsToBtc(it.second)))) }
        outs += JsonObject(mapOf("data" to JsonPrimitive(payload.hex())))
        val funded = node.miner.call("walletcreatefundedpsbt", JsonArray(emptyList()), JsonArray(outs))
            .jsonObject.getValue("psbt").jsonPrimitive.content
        val signed = node.miner.call("walletprocesspsbt", JsonPrimitive(funded))
            .jsonObject.getValue("psbt").jsonPrimitive.content
        pipeline.broadcast(pipeline.finalize(signed))
    }

    @Test
    @Order(5)
    fun withdrawReturnsUnobligatedFunds() {
        val before = escrows.state(escrowId)
        val psbt = escrows.buildWithdraw(escrowId)
        escrows.broadcastPayout(
            listOf(vaultSigner(launcherKey).sign(psbt), vaultSigner(witness1Key).sign(psbt)),
        )
        node.mine(1)
        val after = escrows.state(escrowId)
        assertEquals(EscrowStatus.PENDING, after.status)
        assertEquals(before.balanceSats - before.remainingSats, after.balanceSats)
        assertEquals(0L, after.remainingSats)
    }

    @Test
    @Order(6)
    fun twoPhaseCancellation() {
        escrowId = newPendingEscrow("job-cancel")
        assertTrue(escrows.reserve(escrowId, "rc", witness2, 100_000, 1))
        escrows.requestCancellation(escrowId, null)
        assertEquals(EscrowStatus.TO_CANCEL, escrows.state(escrowId).status)

        assertFailsWith<IllegalArgumentException> { escrows.buildCancel(escrowId) }

        // settle the reserved work first (pay it out), then cancel cleanly
        val settle = PayoutRequest("settle", listOf(node.newAddress() to 100_000L), ByteArray(32))
        val settlePsbt = assertNotNull(escrows.buildPayout(escrowId, settle))
        escrows.broadcastPayout(
            listOf(vaultSigner(launcherKey).sign(settlePsbt), vaultSigner(witness1Key).sign(settlePsbt)),
        )
        node.mine(1)

        val cancelPsbt = escrows.buildCancel(escrowId)
        escrows.broadcastPayout(
            listOf(vaultSigner(launcherKey).sign(cancelPsbt), vaultSigner(witness1Key).sign(cancelPsbt)),
        )
        node.mine(1)
        assertEquals(EscrowStatus.CANCELLED, escrows.state(escrowId).status)
    }

    @Test
    @Order(7)
    fun launchedEscrowSweepsUnilaterally()  {
        val (genesisKey, genesis) = genesisFor("job-sweep")
        val id = escrows.create(genesis, launcher, "job-sweep")
        node.fund(genesis.address, fundSats)
        escrowId = id
        escrows.requestCancellation(id, genesisSigner(genesisKey))
        node.mine(1)
        assertEquals(EscrowStatus.CANCELLED, escrows.state(id).status)
    }

    @Test
    @Order(8)
    fun stakingUnstakeAndTimelockedWithdraw() {
        staking.unstake(launcher)
        assertEquals(0L, staking.available(launcher))

        val dest = node.newAddress()
        val psbt = staking.buildWithdraw(launcher, dest)
        val bond = indexer.withdrawableBonds(launcher).single()
        val signed = pipeline.sign(
            psbt,
            staking.bondDescriptor(launcher, bond.unlockHeight)
                .descriptor.replace(launcher, wif(launcherKey, Network.REGTEST)),
        )
        // premature: consensus rejects until the CLTV height
        val early = pipeline.testAccept(signed)
        assertEquals(false, early.first)

        while (node.height() < bond.unlockHeight) node.mine(1)
        val late = pipeline.sign(staking.buildWithdraw(launcher, dest),
            staking.bondDescriptor(launcher, bond.unlockHeight)
                .descriptor.replace(launcher, wif(launcherKey, Network.REGTEST)))
        staking.broadcast(late)
        node.mine(1)
        assertTrue(node.addressBalance(dest) > 0)
    }

    @Test
    @Order(9)
    fun dashboardStats() {
        val stats = Stats(db).network()
        assertEquals(4, stats.escrowCount)
        assertEquals(2, stats.byStatus[EscrowStatus.CANCELLED])
        assertTrue(stats.totalPaidSats >= 400_000L)
        assertTrue(stats.payoutRecipients >= 4)
    }
}
