package org.hpb.harness

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hpb.androidcore.DashboardModel
import org.hpb.androidcore.OkRelayClient
import org.hpb.androidcore.WitnessSession
import org.hpb.androidcore.WorkerSession
import org.hpb.engine.EscrowStatus
import org.hpb.engine.Network
import org.hpb.engine.escrow.Staking
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrClient
import org.hpb.protocol.Answer
import org.hpb.protocol.AssignmentStatus
import org.hpb.protocol.JobOffer
import org.hpb.protocol.KycPolicy
import org.hpb.protocol.Task
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole
import org.hpb.roles.RoleContext
import org.hpb.roles.WitnessRole
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * K8 core proof: the Android-portable core (OkHttp relay client + worker/
 * witness/dashboard session models — exactly what the Compose shell renders)
 * drives a real job round-trip against the same protocol and relays.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AndroidCoreTest {
    private val node = RegtestNode.start()
    private val relay = RelayFixture.start()

    private fun context(seed: Int): RoleContext {
        val key = ByteArray(32).also { it[29] = 0x77; it[31] = seed.toByte() }
        return RoleContext(
            Network.REGTEST,
            node.rpc,
            Indexer(IndexDb(Files.createTempFile("hpb-ac-$seed", ".sqlite").toString()), node.rpc),
            NostrClient(listOf(relay.url)),
            key,
        )
    }

    private val launcherCtx = context(1)
    private val witnessCtx = context(2)
    private val cosigner2Ctx = context(3)
    private val phoneRelays = OkRelayClient(listOf(relay.url))
    private val phoneWorker = WorkerSession(phoneRelays, ByteArray(32).also { it[31] = 0x78 })

    @AfterAll
    fun tearDown() {
        phoneRelays.close()
        node.close()
        relay.close()
    }

    @Test
    fun phoneWorkerCompletesAJob() {
        Staking(Network.REGTEST, node.rpc, launcherCtx.indexer)
            .stake(launcherCtx.pubkey, 200_000, node.miner)
        node.mine(1)

        val launcher = LauncherRole(launcherCtx)
        val job = launcher.createEscrow("job-android")
        node.fund(job.genesisAddress, 200_000)
        launcher.setupAndOffer(
            job,
            JobOffer(
                escrowId = job.escrowId,
                escrowAddress = job.genesisAddress,
                jobType = "text_answer",
                rewardPerTaskSats = 60_000,
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

        // phone side: discover -> claim -> (launcher grants) -> submit.
        // Relays are eventually consistent (async persistence), so poll.
        val jobRow = await { phoneWorker.openJobs().firstOrNull { it.offer.escrowId == job.escrowId } }
        val workerAddress = node.newAddress()
        phoneWorker.claim(jobRow, workerAddress, emptyList())
        await { launcher.grantClaims(jobRow.event, maxWorkers = 1).takeIf { it.isNotEmpty() } }
        val active = await {
            phoneWorker.assignments(jobRow).firstOrNull { it.status == AssignmentStatus.ACTIVE }
        }
        phoneWorker.submit(jobRow, active, listOf(Answer("t1", "cat")))

        // launcher reveals + pays; witness (JVM role) co-signs
        val submitted = await { launcher.collectSubmissions(jobRow.event).takeIf { it.isNotEmpty() } }
        val results = launcher.revealAndReserve(jobRow.event, submitted)
        val pending = launcher.requestCosign(jobRow.event, results) { workerAddress }

        // the phone's witness view recomputes exactly what the PSBT must pay
        val summary = WitnessSession(phoneRelays).summarize(job.escrowId)
        assertEquals(listOf(workerAddress to 60_000L), summary.expectedLines.map { it.address to it.sats })

        val witnessRole = WitnessRole(witnessCtx)
        await { witnessRole.serveOnce().takeIf { it >= 1 } }
        await { runCatching { launcher.finishPayout(job.escrowId, pending) }.getOrNull() }
        node.mine(1)

        assertEquals(EscrowStatus.COMPLETE, launcherCtx.escrows.state(job.escrowId).status)
        assertEquals(60_000L, node.addressBalance(workerAddress))

        val earning = await { phoneWorker.earnings().firstOrNull() }
        assertEquals(60_000L, earning.sats)
        await {
            phoneWorker.assignments(jobRow)
                .firstOrNull { it.status == AssignmentStatus.VALIDATED }
        }

        val dashboard = DashboardModel(phoneRelays).snapshot()
        assertTrue(dashboard.payouts >= 1)
        assertEquals(60_000L, dashboard.satsPaid)
        assertEquals(1, dashboard.workersPaid)
    }
}
