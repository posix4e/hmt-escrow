package org.hpb.harness

import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import org.hpb.engine.EscrowStatus
import org.hpb.engine.Network
import org.hpb.engine.escrow.Staking
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrClient
import org.hpb.headless.WitnessDaemon
import org.hpb.protocol.Answer
import org.hpb.protocol.JobOffer
import org.hpb.protocol.KycPolicy
import org.hpb.protocol.Task
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole
import org.hpb.roles.RoleContext
import org.hpb.roles.WitnessRole
import org.hpb.roles.WorkerActor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Secondary e2e variant: the witness runs as the headless daemon loop. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadlessWitnessTest {
    private val node = RegtestNode.start()
    private val relay = RelayFixture.start()

    private fun context(seed: Int): RoleContext {
        val key = ByteArray(32).also { it[29] = 0x70; it[31] = seed.toByte() }
        return RoleContext(
            Network.REGTEST,
            node.rpc,
            Indexer(IndexDb(Files.createTempFile("hpb-hl-$seed", ".sqlite").toString()), node.rpc),
            NostrClient(listOf(relay.url)),
            key,
        )
    }

    private val launcherCtx = context(1)
    private val witnessCtx = context(2)
    private val cosigner2Ctx = context(3)

    @AfterAll
    fun tearDown() {
        node.close()
        relay.close()
    }

    @Test
    fun daemonServedRoundTrip() {
        Staking(Network.REGTEST, node.rpc, launcherCtx.indexer)
            .stake(launcherCtx.pubkey, 200_000, node.miner)
        node.mine(1)

        val daemon = WitnessDaemon(WitnessRole(witnessCtx), pollMillis = 200)
        val daemonThread = thread(name = "witnessd") { daemon.run() }
        try {
            val launcher = LauncherRole(launcherCtx)
            val worker = WorkerActor(NostrClient(listOf(relay.url)), ByteArray(32).also { it[31] = 0x71 })
            val workerAddress = node.newAddress()

            val job = launcher.createEscrow("job-headless")
            node.fund(job.genesisAddress, 200_000)
            val offerEvent = launcher.setupAndOffer(
                job,
                JobOffer(
                    escrowId = job.escrowId,
                    escrowAddress = job.genesisAddress,
                    jobType = "text_answer",
                    rewardPerTaskSats = 40_000,
                    tasks = listOf(Task("t1", "cat or dog?")),
                    validation = ValidationPolicy(
                        ValidationType.GROUNDTRUTH,
                        groundtruthHashes = setOf(Validators.groundtruthHash("t1", "cat")),
                    ),
                    kyc = KycPolicy(required = false),
                    expiresAt = System.currentTimeMillis() / 1000 + 3600,
                ),
                witnessCtx.pubkey, cosigner2Ctx.pubkey, cosignerFees = 0 to 0,
            )
            node.mine(1)

            val claim = worker.claim(offerEvent, workerAddress)
            await { launcher.grantClaims(offerEvent, maxWorkers = 1).takeIf { it.isNotEmpty() } }
            await { worker.grantFor(claim)?.takeIf { it.granted } }
            worker.submit(claim, launcherCtx.pubkey, listOf(Answer("t1", "cat")))

            val submitted = await { launcher.collectSubmissions(offerEvent).takeIf { it.isNotEmpty() } }
            val results = launcher.revealAndReserve(offerEvent, submitted)
            val pending = launcher.requestCosign(offerEvent, results) { workerAddress }

            // the daemon loop picks the request up on its own; poll for the response
            val receipt = await { runCatching { launcher.finishPayout(job.escrowId, pending) }.getOrNull() }
            node.mine(1)

            assertEquals(EscrowStatus.COMPLETE, launcherCtx.escrows.state(job.escrowId).status)
            assertEquals(40_000L, node.addressBalance(workerAddress))
            assertEquals(receipt.txid, launcherCtx.escrows.payoutTxid(job.escrowId, receipt.payoutId))
        } finally {
            daemon.stop()
            daemonThread.join(5000)
        }
    }
}
