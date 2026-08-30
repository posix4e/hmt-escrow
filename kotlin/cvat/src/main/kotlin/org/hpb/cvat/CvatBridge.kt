package org.hpb.cvat

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hpb.engine.nostr.NostrEvent
import org.hpb.headless.DemoConfig
import org.hpb.headless.RoleStack
import org.hpb.protocol.JobOffer
import org.hpb.protocol.KycPolicy
import org.hpb.protocol.Receipt
import org.hpb.protocol.Task
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole

/**
 * Outsource a CVAT task's annotation to the network and get the labels
 * back as CVAT tag annotations — CVAT stays the dataset/QA tool, the
 * protocol supplies the workers, the escrow, and the on-chain payout.
 *
 * Export: each frame becomes one job task whose question carries the frame
 * image inline (data URI) plus the CVAT label schema as choices — workers
 * need only a relay connection, never CVAT access. Import: the validated
 * consensus answer per frame is appended as a tag annotation.
 */
class CvatBridge(
    private val cvat: CvatClient,
    cfg: DemoConfig,
    private val log: (String) -> Unit = ::println,
) {
    private val stack = RoleStack(cfg, log)
    private val rewardSats = cfg.rewardSats

    data class Outcome(val receipt: Receipt, val tags: List<CvatTag>)

    fun run(taskId: Long, workersWanted: Int = 1, maxFrames: Int = Int.MAX_VALUE): Outcome {
        stack.preflight()
        stack.ensureStake()
        val labels = cvat.labels(taskId)
        val tasks = exportTasks(taskId, labels, maxFrames)
        val (job, offerEvent) = publish(taskId, tasks, labels, workersWanted)
        val submitted = collectLabels(offerEvent, labels, tasks.size, workersWanted)
        val receipt = stack.settle(job.escrowId, offerEvent, submitted)
        val tags = importTags(taskId, labels, submitted, workersWanted)
        check(tags.size == tasks.size) {
            "imported ${tags.size} tags for ${tasks.size} frames — refusing to report success"
        }
        return Outcome(receipt, tags)
    }

    private fun exportTasks(taskId: Long, labels: List<CvatLabel>, maxFrames: Int): List<Task> {
        val name = cvat.taskName(taskId)
        val total = cvat.frameCount(taskId)
        val frames = minOf(total, maxFrames)
        if (frames < total) log("NOTE: exporting only $frames of $total frames (limit)")
        log("cvat task '$name': exporting $frames frames, labels ${labels.map { it.name }}")
        return (0 until frames).map { n ->
            val image = Base64.getEncoder().encodeToString(cvat.frame(taskId, n))
            Task(
                key = "frame-$n",
                question = JsonObject(
                    mapOf(
                        "text" to JsonPrimitive("Label frame $n of \"$name\""),
                        "image" to JsonPrimitive("data:image/png;base64,$image"),
                        "choices" to JsonArray(labels.map { JsonPrimitive(it.name) }),
                    ),
                ).toString(),
            )
        }
    }

    private fun publish(
        taskId: Long,
        tasks: List<Task>,
        labels: List<CvatLabel>,
        workersWanted: Int,
    ): Pair<LauncherRole.LaunchedJob, NostrEvent> {
        val job = stack.launcher.createEscrow("cvat-$taskId-${System.currentTimeMillis() / 1000}")
        stack.fundGenesis(job, tasks.size * rewardSats * workersWanted + FUND_BUFFER_SATS)
        val offerEvent = stack.launcher.setupAndOffer(
            job,
            JobOffer(
                escrowId = job.escrowId,
                escrowAddress = job.genesisAddress,
                jobType = "image_label (${labels.joinToString("/") { it.name }})",
                rewardPerTaskSats = rewardSats,
                tasks = tasks,
                validation = ValidationPolicy(
                    ValidationType.AGREEMENT,
                    assignmentsPerTask = workersWanted,
                    agreementThreshold = AGREEMENT_THRESHOLD,
                ),
                kyc = KycPolicy(required = false),
                expiresAt = System.currentTimeMillis() / 1000 + 86_400,
            ),
            stack.witnessCtx.pubkey, stack.cosigner2Ctx.pubkey, cosignerFees = 0 to 0,
        )
        stack.awaitVaultConfirmed(job.escrowId)
        log("offer published — ${tasks.size} frames at $rewardSats sats each; waiting for workers…")
        return job to offerEvent
    }

    /** Grant claims as they arrive; done when every frame has enough
     *  SCHEMA-VALID labels. An answer outside CVAT's label set never counts
     *  toward completion, is never revealed, and is never paid — otherwise
     *  the escrow could settle while CVAT receives fewer annotations. */
    private fun collectLabels(
        offerEvent: NostrEvent,
        labels: List<CvatLabel>,
        frames: Int,
        workersWanted: Int,
    ): List<Validators.Submitted> {
        val schema = labels.map { Validators.normalize(it.name) }.toSet()
        return stack.await("labels from the network") {
            stack.launcher.grantClaims(offerEvent, maxWorkers = workersWanted)
            stack.launcher.collectSubmissions(offerEvent)
                .filter { Validators.normalize(it.answer) in schema }
                .takeIf { rows ->
                    (0 until frames).all { n -> rows.count { it.taskKey == "frame-$n" } >= workersWanted }
                }
        }
    }

    /** The validated consensus answer per frame becomes a CVAT tag. */
    private fun importTags(
        taskId: Long,
        labels: List<CvatLabel>,
        submitted: List<Validators.Submitted>,
        workersWanted: Int,
    ): List<CvatTag> {
        val accepted = Validators.validate(
            ValidationPolicy(
                ValidationType.AGREEMENT,
                assignmentsPerTask = workersWanted,
                agreementThreshold = AGREEMENT_THRESHOLD,
            ),
            submitted,
        ).filter { it.accepted }
        val byName = labels.associateBy { Validators.normalize(it.name) }
        val tags = accepted.groupBy { it.taskKey }.mapNotNull { (key, rows) ->
            byName[Validators.normalize(rows.first().answer)]?.let {
                CvatTag(frame = key.removePrefix("frame-").toInt(), labelId = it.id)
            }
        }.sortedBy { it.frame }
        cvat.appendTags(taskId, tags)
        log("imported ${tags.size} tag annotations back into cvat task $taskId")
        return tags
    }

    private companion object {
        const val FUND_BUFFER_SATS = 20_000L
        const val AGREEMENT_THRESHOLD = 0.5
    }
}

fun main() {
    val cvat = CvatClient(
        System.getenv("CVAT_URL") ?: "http://127.0.0.1:7688",
        System.getenv("CVAT_TOKEN") ?: "mock",
    )
    val taskId = System.getenv("CVAT_TASK_ID")?.toLong() ?: MockCvat.TASK_ID
    val workers = System.getenv("HPB_CVAT_WORKERS")?.toInt() ?: 1
    val maxFrames = System.getenv("HPB_MAX_FRAMES")?.toInt() ?: Int.MAX_VALUE
    val outcome = CvatBridge(cvat, DemoConfig.fromEnv()).run(taskId, workers, maxFrames)
    println("bridge complete: ${outcome.tags.size} frames labeled, paid in ${outcome.receipt.txid}")
}
