package org.hpb.cvat

import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.headless.DemoConfig
import org.hpb.headless.RoleStack
import org.hpb.protocol.CvatAccessCodec
import org.hpb.protocol.CvatIdentity
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.WorkSource
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.JobOffer
import org.hpb.protocol.KycPolicy
import org.hpb.protocol.Receipt
import org.hpb.protocol.Task
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole

/**
 * Runs one live job whose work lives in CVAT, and waits for a human to do it.
 *
 * The difference from [CvatBridge] is the whole point of the redesign: nothing
 * is copied out of CVAT. The offer carries a reference, the worker annotates in
 * CVAT's own editor, and recording roles read the result back. What gets paid
 * is what two recorders agree the worker drew, checked against the hash the
 * worker published before anyone revealed anything.
 */
class CvatLiveJob(
    private val org: CvatOrg,
    private val cfg: DemoConfig,
    private val cvatBaseUrl: String,
    private val log: (String) -> Unit = ::println,
) {
    private val stack = RoleStack(cfg, log)

    data class Outcome(val receipt: Receipt, val workspace: CvatWorkspace)

    fun run(workersWanted: Int = 1, webhookUrl: String? = null): Outcome {
        stack.preflight()
        stack.ensureStake()
        val exchange = CvatExchangeRole(org, stack.launcherCtx.nostr, stack.launcherCtx.privkey, cvatBaseUrl, log)
        val workspace = exchange.provision(slug(), DemoFrames.LABELS, DemoFrames.frames())
        webhookUrl?.let {
            exchange.registerWebhook(workspace, it, WEBHOOK_SECRET)
            log("registered cvat webhook -> $it")
        }
        val tasks = exchange.tasks(workspace)
        val (job, offerEvent) = publish(workspace, tasks, workersWanted)
        announce(tasks)
        val submitted = collect(exchange, workspace, job.escrowId, offerEvent, tasks, workersWanted)
        val pulled = pull(job.escrowId, workspace, tasks)
        val receipt = stack.settle(job.escrowId, offerEvent, submitted, pulled)
        return Outcome(receipt, workspace)
    }

    /** What a worker must produce for each job, and therefore what it is paid for. */
    private fun groundtruth(workspace: CvatWorkspace, tasks: List<Task>): Map<String, String> =
        org.jobs(workspace.taskId).associate { job ->
            val frames = (job.startFrame..job.stopFrame).map { it to DemoFrames.SHAPES[it] }
            "unit-${job.id}" to ExternalWork.canonical(WorkSource.RESULT_TAGS, frames)
        }.filterKeys { key -> tasks.any { it.key == key } }

    private fun publish(
        workspace: CvatWorkspace,
        tasks: List<Task>,
        workersWanted: Int,
    ): Pair<LauncherRole.LaunchedJob, NostrEvent> {
        val expected = groundtruth(workspace, tasks)
        val job = stack.launcher.createEscrow("cvat-${workspace.taskId}-${System.currentTimeMillis() / 1000}")
        stack.fundGenesis(job, tasks.size * cfg.rewardSats * workersWanted + FUND_BUFFER_SATS)
        val offerEvent = stack.launcher.setupAndOffer(
            job,
            JobOffer(
                escrowId = job.escrowId,
                escrowAddress = job.genesisAddress,
                jobType = "cvat_tags (${workspace.labels.joinToString("/")})",
                rewardPerTaskSats = cfg.rewardSats,
                tasks = tasks,
                validation = ValidationPolicy(
                    ValidationType.GROUNDTRUTH,
                    groundtruthHashes = expected.map { Validators.groundtruthHash(it.key, it.value) }.toSet(),
                ),
                kyc = KycPolicy(required = false),
                expiresAt = System.currentTimeMillis() / 1000 + OFFER_TTL_SECONDS,
            ),
            stack.witnessCtx.pubkey, stack.cosigner2Ctx.pubkey, cosignerFees = 0 to 0,
        )
        stack.awaitVaultConfirmed(job.escrowId)
        return job to offerEvent
    }

    private fun announce(tasks: List<Task>) {
        log("")
        log("offer is live — ${tasks.size} cvat job(s) at ${cfg.rewardSats} sats each")
        tasks.forEach { task ->
            ExternalWork.workSource(task.question)?.let { log("  ${task.key}: ${it.url}") }
        }
        log("register a CVAT account at $cvatBaseUrl, then claim the job in the app")
        log("")
    }

    /** Grant claims and admit workers until everyone has submitted. */
    private fun collect(
        exchange: CvatExchangeRole,
        workspace: CvatWorkspace,
        escrowId: String,
        offerEvent: NostrEvent,
        tasks: List<Task>,
        workersWanted: Int,
    ): List<Validators.Submitted> = stack.await("a worker to finish in cvat") {
        stack.launcher.grantClaims(offerEvent, maxWorkers = workersWanted)
        exchange.serveAccessRequests(escrowId, workspace)
        stack.launcher.collectSubmissions(offerEvent).takeIf { rows ->
            tasks.all { task -> rows.count { it.taskKey == task.key } >= workersWanted }
        }
    }

    /** Two recorders, and only what they agree on. */
    private fun pull(
        escrowId: String,
        workspace: CvatWorkspace,
        tasks: List<Task>,
    ): Map<Pair<String, String>, String> {
        val client = CvatClient(org.http)
        val labels = client.labels(workspace.taskId).associate { it.id to it.name }
        val assignments = assignments(escrowId, workspace, tasks)
        val first = CvatRecordingRole(client, labels).pullAll(assignments)
        val second = CvatRecordingRole(CvatClient(org.http), labels).pullAll(assignments)
        val agreed = CvatRecordingRole.agree(first, second)
        log("recorders agreed on ${agreed.size} of ${first.size} pulled annotation sets")
        return agreed
    }

    /**
     * Which Nostr worker holds which CVAT job, derived entirely from public
     * events plus CVAT's own assignee — the same two sources any observer has.
     */
    private fun assignments(
        escrowId: String,
        workspace: CvatWorkspace,
        tasks: List<Task>,
    ): List<CvatAssignment> {
        val grants = stack.launcherCtx.nostr.fetch(
            NostrFilter(
                kinds = listOf(ProtocolKinds.CVAT_ACCESS_GRANT),
                xTag = escrowId,
                limit = FETCH_LIMIT,
            ),
        )
        val workerByCvatId = CvatIdentity.admitted(grants.mapNotNull(CvatAccessCodec::binding))
            .entries.associate { (worker, cvatId) -> cvatId to worker }
        val jobs = org.jobs(workspace.taskId).associateBy { it.id }
        return tasks.mapNotNull { task ->
            val work = ExternalWork.workSource(task.question) ?: return@mapNotNull null
            val unit = CvatTool.unitId(work) ?: return@mapNotNull null
            val assignee = jobs[unit]?.assigneeId ?: return@mapNotNull null
            workerByCvatId[assignee]?.let { CvatAssignment(it, task.key, unit) }
        }
    }

    /** CVAT caps an organization slug at 16 characters. */
    private fun slug() = "hpb-${System.currentTimeMillis() / 1000 % SLUG_SUFFIX_BOUND}"

    private companion object {
        const val FUND_BUFFER_SATS = 20_000L
        const val OFFER_TTL_SECONDS = 86_400L
        const val WEBHOOK_SECRET = "hpb-cvat-webhook"
        const val FETCH_LIMIT = 500
        const val SLUG_SUFFIX_BOUND = 100_000_000L
    }
}

/**
 * Host one live CVAT job and wait for a human to work it.
 *
 * Raise HPB_MAX_WAIT_MS well above its default: every wait is capped by it,
 * including the one for a person to finish annotating in a browser.
 */
fun main() {
    val url = System.getenv("CVAT_URL") ?: error("CVAT_URL is required")
    val token = System.getenv("CVAT_TOKEN") ?: error("CVAT_TOKEN is required")
    val outcome = CvatLiveJob(CvatOrg(url, token), DemoConfig.fromEnv(), url).run(
        workersWanted = System.getenv("HPB_CVAT_WORKERS")?.toInt() ?: 1,
        webhookUrl = System.getenv("HPB_WEBHOOK_URL"),
    )
    println("paid in ${outcome.receipt.txid}")
}
