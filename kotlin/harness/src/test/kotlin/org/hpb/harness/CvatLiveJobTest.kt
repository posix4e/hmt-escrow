package org.hpb.harness

import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.cvat.CvatClient
import org.hpb.cvat.CvatLiveJob
import org.hpb.cvat.CvatOrg
import org.hpb.cvat.DemoFrames
import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.nostr.NostrFilter
import org.hpb.headless.DemoConfig
import org.hpb.protocol.Assignments
import org.hpb.protocol.CvatAccessCodec
import org.hpb.protocol.CvatAccessRequest
import org.hpb.protocol.CvatCommitment
import org.hpb.protocol.CvatCommitments
import org.hpb.protocol.CvatCompletion
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.Offers
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Submission
import org.hpb.protocol.Answer
import org.hpb.protocol.Claim
import org.hpb.engine.Secp
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The whole thing, against real services: a job whose work lives in CVAT, a
 * worker that annotates it with its own credentials, and a payout confirmed
 * on-chain.
 *
 * The worker here is scripted rather than a phone, but it holds its own Nostr
 * key and its own CVAT account and the launcher never has either — so what is
 * proved is the same thing the phone will do.
 */
class CvatLiveJobTest {
    private val workerKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val worker = Secp.xonlyHex(workerKey)

    @Test
    fun `a cvat job pays out on regtest`() {
        assumeTrue(RealCvat.enabled && RealCvat.relays != null, "CVAT/relay unset")
        assumeTrue(System.getenv("HPB_RPC_URL") != null, "HPB_RPC_URL unset")
        val cfg = DemoConfig.fromEnv()
        val org = CvatOrg(RealCvat.http())
        val strays = mutableListOf<Long>()

        val annotator = thread(name = "scripted-worker") { runCatching { work(cfg, org, strays) } }
        val outcome = CvatLiveJob(org, cfg, RealCvat.baseUrl!!).run(workersWanted = 1)
        annotator.join(WORKER_JOIN_MS)

        try {
            assertTrue(outcome.receipt.txid.isNotBlank(), "no payout transaction")
            val paid = outcome.receipt.lines.sumOf { it.sats }
            assertEquals(cfg.rewardSats, paid, "worker was not paid exactly one task reward")
        } finally {
            org.deleteProject(outcome.workspace.projectId)
            org.deleteOrganization(outcome.workspace.orgId)
            strays.forEach(RealCvat::deleteUser)
        }
    }

    /** Everything the phone will do, scripted. */
    private fun work(cfg: DemoConfig, org: CvatOrg, strays: MutableList<Long>) {
        val username = RealCvat.unique("hpbw")
        val email = "$username@localhost.invalid"
        val cvatToken = RealCvat.registerWorker(username, email, WORKER_PASSWORD)
        strays += RealCvat.selfId(cvatToken)

        NostrClient(RealCvat.relayList()).use { nostr ->
            val offerEvent = await("an offer") {
                nostr.fetch(NostrFilter(kinds = listOf(ProtocolKinds.JOB_OFFER), limit = 50))
                    .firstOrNull { Offers.fromEvent(it).jobType.startsWith("cvat_tags") }
            }
            val offer = Offers.fromEvent(offerEvent)
            val payout = cfg.rpc.wallet(cfg.walletName).call("getnewaddress").jsonPrimitive.content

            val claim = Assignments.claim(
                workerKey, offerEvent.pubkey,
                Claim(offerEvent.id, offer.escrowId, payout, emptyList()),
                now(),
            )
            check(nostr.publish(claim)) { "claim publish failed" }
            requestAccess(nostr, offerEvent.pubkey, claim.id, offer.escrowId, email)

            val grant = await("cvat access") {
                nostr.fetch(
                    NostrFilter(kinds = listOf(ProtocolKinds.CVAT_ACCESS_GRANT), pTag = worker, limit = 50),
                ).firstOrNull()
            }
            val access = CvatAccessCodec.parseGrant(grant, workerKey)
            RealCvat.acceptInvitation(cvatToken, access.invitationKey)

            annotateAndSubmit(nostr, org, offerEvent, offer.escrowId, cvatToken)
        }
    }

    private fun requestAccess(
        nostr: NostrClient,
        launcher: String,
        claimId: String,
        escrowId: String,
        email: String,
    ) {
        val request = CvatAccessCodec.request(
            workerKey, launcher, CvatAccessRequest(claimId, escrowId, email), now(),
        )
        check(nostr.publish(request)) { "access request publish failed" }
    }

    /** Annotate in CVAT, commit publicly to the result, then assert completion. */
    private fun annotateAndSubmit(
        nostr: NostrClient,
        org: CvatOrg,
        offerEvent: org.hpb.engine.nostr.NostrEvent,
        escrowId: String,
        cvatToken: String,
    ) {
        val offer = Offers.fromEvent(offerEvent)
        val task = offer.tasks.first()
        val work = assertNotNull(ExternalWork.workSource(task.question), "task has no work source")
        val labels = CvatClient(RealCvat.http()).labels(work.taskId).associateBy { it.name }
        val jobs = org.jobs(work.taskId)
        val job = jobs.first { it.id == work.jobId }

        val correct = (job.startFrame..job.stopFrame).map { it to labels.getValue(DemoFrames.SHAPES[it]).id }
        RealCvat.annotateJob(cvatToken, work.jobId, correct)

        val canonical = ExternalWork.canonicalAnnotations(
            (job.startFrame..job.stopFrame).map { it to DemoFrames.SHAPES[it] },
        )
        val commitment = CvatCommitments.toEvent(
            workerKey,
            CvatCommitment(escrowId, task.key, worker, ExternalWork.hashOf(canonical)),
            now(),
        )
        check(nostr.publish(commitment)) { "commitment publish failed" }

        val grantEventId = await("a granted assignment") {
            nostr.fetch(NostrFilter(kinds = listOf(ProtocolKinds.GRANT), pTag = worker, limit = 50))
                .firstOrNull { Assignments.parseGrant(it).granted }?.id
        }
        val answer = ExternalWork.answer(
            CvatCompletion(work.jobId, cvatUserId = 0, annotationsSha256 = ExternalWork.hashOf(canonical)),
        )
        val submission = Assignments.submission(
            workerKey, offerEvent.pubkey,
            Submission(grantEventId, escrowId, listOf(Answer(task.key, answer))),
            now(),
        )
        check(nostr.publish(submission)) { "submission publish failed" }
    }

    private fun <T : Any> await(what: String, probe: () -> T?): T {
        repeat(POLL_ATTEMPTS) {
            runCatching(probe).getOrNull()?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("timed out waiting for $what")
    }

    private fun now() = System.currentTimeMillis() / 1000

    private companion object {
        const val WORKER_PASSWORD = "qN7vzLd2Wm-Rk9tf"
        const val POLL_ATTEMPTS = 120
        const val POLL_INTERVAL_MS = 3_000L
        const val WORKER_JOIN_MS = 60_000L
    }
}
