package org.hpb.harness

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.EscrowStatus
import org.hpb.engine.Network
import org.hpb.engine.OpReturn
import org.hpb.engine.btcToSats
import org.hpb.engine.escrow.Staking
import org.hpb.engine.hex
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.Answer
import org.hpb.protocol.JobOffer
import org.hpb.protocol.KycPolicy
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Receipts
import org.hpb.protocol.Task
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole
import org.hpb.roles.MockAttester
import org.hpb.roles.RoleContext
import org.hpb.roles.WitnessRole
import org.hpb.roles.WorkerActor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * THE CORE DEMO: a complete serverless job round-trip on regtest — no
 * servers anywhere. The launcher runs the job from its client; workers hold
 * only keys; an independent witness verifies everything from first
 * principles (its own index, its own recomputation) before co-signing; the
 * payout lands on-chain; receipts verify against the chain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2eRoundTripTest {
    private val node = RegtestNode.start()
    private val relay = RelayFixture.start()

    private fun context(seed: Int): RoleContext {
        val key = ByteArray(32).also { it[29] = 0x50; it[31] = seed.toByte() }
        return RoleContext(
            Network.REGTEST,
            node.rpc,
            Indexer(IndexDb(Files.createTempFile("hpb-e2e-$seed", ".sqlite").toString()), node.rpc),
            NostrClient(listOf(relay.url)),
            key,
        )
    }

    private val launcherCtx = context(1)
    private val witnessCtx = context(2)
    private val cosigner2Ctx = context(3)
    private val launcher = LauncherRole(launcherCtx)
    private val witness = WitnessRole(witnessCtx)
    private val attester = MockAttester(NostrClient(listOf(relay.url)), ByteArray(32).also { it[31] = 0x66 })

    private fun worker(seed: Int) =
        WorkerActor(NostrClient(listOf(relay.url)), ByteArray(32).also { it[30] = 0x60; it[31] = seed.toByte() })

    private val w1 = worker(1)
    private val w2 = worker(2)
    private val w3 = worker(3)
    private val addresses = mutableMapOf<String, String>()

    private fun payoutAddress(worker: WorkerActor): String =
        addresses.getOrPut(worker.pubkey) { node.newAddress() }

    @AfterAll
    fun tearDown() {
        node.close()
        relay.close()
    }

    private fun offerFor(escrowId: String, address: String, validation: ValidationPolicy, kyc: KycPolicy) =
        JobOffer(
            escrowId = escrowId,
            escrowAddress = address,
            jobType = "text_answer",
            rewardPerTaskSats = 50_000,
            tasks = listOf(Task("t1", "cat or dog?"), Task("t2", "2+2?")),
            validation = validation,
            kyc = kyc,
            expiresAt = System.currentTimeMillis() / 1000 + 3600,
        )

    private fun runJob(
        jobId: String,
        validation: ValidationPolicy,
        kyc: KycPolicy,
        fundSats: Long,
        participants: List<Pair<WorkerActor, List<Answer>>>,
        attestFor: List<WorkerActor> = emptyList(),
    ): Pair<String, org.hpb.protocol.Receipt> {
        val job = launcher.createEscrow(jobId)
        node.fund(job.genesisAddress, fundSats)
        val offerEvent = launcher.setupAndOffer(
            job, offerFor(job.escrowId, job.genesisAddress, validation, kyc),
            witnessCtx.pubkey, cosigner2Ctx.pubkey, cosignerFees = 2 to 0,
        )
        node.mine(1)

        val attestations = attestFor.associate { actor ->
            actor.pubkey to attester.issue(
                actor.pubkey, "org.humanprotocol.kyc.mock.v1",
                System.currentTimeMillis() / 1000 + 86_400,
            ).id
        }
        val claims = participants.associate { (actor, _) ->
            actor.pubkey to actor.claim(
                offerEvent, payoutAddress(actor), listOfNotNull(attestations[actor.pubkey]),
            )
        }
        await {
            launcher.grantClaims(offerEvent, maxWorkers = participants.size)
                .takeIf { it.size >= participants.size }
        }

        participants.forEach { (actor, answers) ->
            val grant = await { actor.grantFor(claims.getValue(actor.pubkey)) }
            if (grant.granted) {
                actor.submit(claims.getValue(actor.pubkey), launcherCtx.pubkey, answers)
            }
        }

        val expectSubmissions = participants.count { (actor, _) ->
            await { actor.grantFor(claims.getValue(actor.pubkey)) }.granted
        }
        val submitted = await {
            launcher.collectSubmissions(offerEvent)
                .takeIf { it.map { s -> s.worker }.distinct().size >= expectSubmissions }
        }
        val results = launcher.revealAndReserve(offerEvent, submitted)
        val pending = launcher.requestCosign(offerEvent, results) { workerPk ->
            addresses.getValue(workerPk)
        }
        await { witness.serveOnce().takeIf { it >= 1 } }
        val receipt = await { runCatching { launcher.finishPayout(job.escrowId, pending) }.getOrNull() }
        node.mine(1)
        return job.escrowId to receipt
    }

    @Test
    @Order(1)
    fun stakeOnce() {
        Staking(Network.REGTEST, node.rpc, launcherCtx.indexer).stake(launcherCtx.pubkey, 200_000, node.miner)
        node.mine(1)
    }

    @Test
    @Order(2)
    fun agreementValidatedJob() {
        val (escrowId, receipt) = runJob(
            "job-agreement",
            ValidationPolicy(ValidationType.AGREEMENT, assignmentsPerTask = 3, agreementThreshold = 0.5),
            KycPolicy(required = false),
            fundSats = 400_000,
            participants = listOf(
                w1 to listOf(Answer("t1", "cat"), Answer("t2", "4")),
                w2 to listOf(Answer("t1", "Cat "), Answer("t2", "4")),
                w3 to listOf(Answer("t1", "dog"), Answer("t2", "4")),
            ),
        )
        // consensus: t1 = "cat" (2/3), t2 = "4" (3/3): w1/w2 earn 2 tasks, w3 earns 1
        assertEquals(EscrowStatus.COMPLETE, launcherCtx.escrows.state(escrowId).status)
        assertEquals(EscrowStatus.COMPLETE, witnessCtx.escrows.state(escrowId).status)
        assertEquals(100_000L, node.addressBalance(addresses.getValue(w1.pubkey)))
        assertEquals(100_000L, node.addressBalance(addresses.getValue(w2.pubkey)))
        assertEquals(50_000L, node.addressBalance(addresses.getValue(w3.pubkey)))
        assertReceiptMatchesChain(receipt)
        // witness fee output (2% of 400k = 8k) at the witness identity address
        assertEquals(
            8_000L,
            node.addressBalance(launcherCtx.escrows.identityAddress(witnessCtx.pubkey)),
        )
    }

    @Test
    @Order(3)
    fun groundtruthValidatedJob() {
        addresses.clear()
        val groundtruth = setOf(
            Validators.groundtruthHash("t1", "cat"),
            Validators.groundtruthHash("t2", "4"),
        )
        val (escrowId, receipt) = runJob(
            "job-groundtruth",
            ValidationPolicy(ValidationType.GROUNDTRUTH, groundtruthHashes = groundtruth),
            KycPolicy(required = false),
            fundSats = 300_000,
            participants = listOf(
                w1 to listOf(Answer("t1", "CAT"), Answer("t2", "4")),
                w2 to listOf(Answer("t1", "cat"), Answer("t2", "5")),
            ),
        )
        assertEquals(EscrowStatus.COMPLETE, witnessCtx.escrows.state(escrowId).status)
        assertEquals(100_000L, node.addressBalance(addresses.getValue(w1.pubkey)))
        assertEquals(50_000L, node.addressBalance(addresses.getValue(w2.pubkey)))
        assertReceiptMatchesChain(receipt)
    }

    @Test
    @Order(4)
    fun kycGatedJob() {
        addresses.clear()
        val (escrowId, receipt) = runJob(
            "job-kyc",
            ValidationPolicy(
                ValidationType.GROUNDTRUTH,
                groundtruthHashes = setOf(
                    Validators.groundtruthHash("t1", "cat"),
                    Validators.groundtruthHash("t2", "4"),
                ),
            ),
            KycPolicy(required = true, attesters = listOf(attester.pubkey)),
            fundSats = 300_000,
            participants = listOf(
                w1 to listOf(Answer("t1", "cat"), Answer("t2", "4")),
                w2 to listOf(Answer("t1", "cat"), Answer("t2", "4")),
            ),
            attestFor = listOf(w1), // w2 has no attestation and must be rejected
        )
        assertEquals(EscrowStatus.COMPLETE, witnessCtx.escrows.state(escrowId).status)
        assertEquals(100_000L, node.addressBalance(addresses.getValue(w1.pubkey)))
        assertEquals(0L, node.addressBalance(addresses.getValue(w2.pubkey)))
        val w2Grant = witnessCtx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.GRANT), pTag = w2.pubkey, xTag = escrowId),
        ).map(org.hpb.protocol.Assignments::parseGrant)
        assertTrue(w2Grant.isNotEmpty() && w2Grant.none { it.granted })
        assertReceiptMatchesChain(receipt)
    }

    /** Wallet-side audit: the receipt's txid must carry the PAYOUT commitment
     *  and outputs covering every receipt line. */
    private fun assertReceiptMatchesChain(receipt: org.hpb.protocol.Receipt) {
        val event = witnessCtx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.RECEIPT), xTag = receipt.escrowId),
        ).first()
        val fetched = Receipts.fromEvent(event)
        assertEquals(receipt.txid, fetched.txid)

        val tx = node.rpc.call(
            "getrawtransaction", JsonPrimitive(fetched.txid), JsonPrimitive(true),
        ).jsonObject
        val outputs = tx.getValue("vout").jsonArray.map { it.jsonObject }
        val tag = outputs.firstNotNullOf { out ->
            OpReturn.decodeScriptPubKey(
                out.getValue("scriptPubKey").jsonObject.getValue("hex").jsonPrimitive.content,
            ) as? OpReturn.Payout
        }
        assertEquals(OpReturn.payoutIdHash(fetched.payoutId).hex(), tag.payoutIdHash.hex())
        assertTrue(tag.finalized)
        fetched.lines.forEach { line ->
            assertTrue(
                outputs.any { out ->
                    out.getValue("scriptPubKey").jsonObject["address"]?.jsonPrimitive?.content ==
                        line.address &&
                        btcToSats(out.getValue("value").jsonPrimitive.content) == line.sats
                },
                "receipt line not covered on-chain: $line",
            )
        }
        assertFalse(fetched.lines.isEmpty())
    }
}
