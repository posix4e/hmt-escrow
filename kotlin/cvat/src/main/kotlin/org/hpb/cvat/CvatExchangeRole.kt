package org.hpb.cvat

import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.CvatAccessCodec
import org.hpb.protocol.CvatAccessGrant
import org.hpb.protocol.CvatIdentity
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Task

/** A provisioned CVAT project: where the work is and how it is reached. */
data class CvatWorkspace(
    val orgId: Long,
    val orgSlug: String,
    val projectId: Long,
    val taskId: Long,
    val labels: List<String>,
    val baseUrl: String,
)

/**
 * The launcher's CVAT side: publish work into an organization it owns, and
 * admit workers to it one at a time.
 *
 * The reason this exists rather than [CvatBridge]: work stays in CVAT and the
 * worker is brought to it, instead of frames being copied out into offers. The
 * launcher can assign and revoke, but never holds a worker's credentials, so it
 * cannot author annotations as them.
 */
class CvatExchangeRole(
    private val org: CvatOrg,
    private val nostr: NostrClient,
    private val privkey: ByteArray,
    private val baseUrl: String,
    private val log: (String) -> Unit = ::println,
) {
    /** Stand up an organization, a project and a task holding [frames]. */
    fun provision(slug: String, labels: List<String>, frames: List<Pair<String, ByteArray>>): CvatWorkspace {
        val orgId = org.createOrganization(slug, "hpb $slug")
        val projectId = org.createProject(slug, "hpb labeling", labels)
        val taskId = org.createTask(slug, projectId, "hpb task")
        org.uploadFrames(taskId, frames)
        org.awaitDataReady(taskId)
        log("provisioned cvat org $slug, project $projectId, task $taskId with ${frames.size} frames")
        return CvatWorkspace(orgId, slug, projectId, taskId, labels, baseUrl)
    }

    fun registerWebhook(workspace: CvatWorkspace, targetUrl: String, secret: String): Long =
        org.registerWebhook(workspace.orgSlug, workspace.projectId, targetUrl, secret)

    /**
     * One protocol task per CVAT job — CVAT's own unit of assignment. The task
     * carries a reference, not pixels, and the reference is committed on-chain
     * because the manifest already hashes every question.
     */
    fun tasks(workspace: CvatWorkspace): List<Task> = org.jobs(workspace.taskId).map { job ->
        val work = CvatTool.workSource(workspace, job.id)
        Task(
            // Tool-neutral: this key is hashed into the on-chain manifest, so
            // baking a tool name into it would harden a CVAT assumption into
            // the escrow commitment itself.
            key = "unit-${job.id}",
            question = ExternalWork.question(
                "Annotate job ${job.id} — labels: ${workspace.labels.joinToString(", ")}",
                work,
            ),
        )
    }

    /**
     * Answer every access request for this escrow that is not already answered.
     *
     * Idempotent, because it runs on a poll loop: a worker already bound to a
     * CVAT account is skipped rather than re-invited.
     */
    fun serveAccessRequests(escrowId: String, workspace: CvatWorkspace): List<NostrEvent> {
        val granted = existingBindings(escrowId)
        return requests(escrowId)
            .filter { it.pubkey !in granted.keys }
            .mapNotNull { admit(it, escrowId, workspace, granted) }
    }

    private fun requests(escrowId: String): List<NostrEvent> =
        nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.CVAT_ACCESS_REQUEST), xTag = escrowId, limit = FETCH_LIMIT),
        ).sortedWith(compareBy({ it.createdAt }, { it.id }))

    /** The already-admitted worker → CVAT account map, as any witness derives it. */
    private fun existingBindings(escrowId: String): Map<String, Long> {
        val events = nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.CVAT_ACCESS_GRANT), xTag = escrowId, limit = FETCH_LIMIT),
        )
        return CvatIdentity.admitted(events.mapNotNull(CvatAccessCodec::binding))
    }

    private fun admit(
        request: NostrEvent,
        escrowId: String,
        workspace: CvatWorkspace,
        granted: Map<String, Long>,
    ): NostrEvent? {
        val parsed = runCatching { CvatAccessCodec.parseRequest(request, privkey) }.getOrNull() ?: return null

        // The guard witnesses re-run: one CVAT account backs one worker. An
        // account already in this org was put there by admitting someone, so
        // CVAT's own refusal is the duplicate signal — there is no way to look
        // an address up beforehand.
        val admission = org.invite(workspace.orgSlug, parsed.cvatEmail)
        if (admission !is CvatAdmission.Invited) {
            log("refusing ${request.pubkey.take(8)}…: that cvat account already backs another worker")
            return null
        }
        val invitation = admission.invitation
        if (invitation.userId in granted.values) {
            log("refusing ${request.pubkey.take(8)}…: cvat user ${invitation.userId} already bound")
            return null
        }

        val job = assignFreeJob(workspace, invitation.userId) ?: run {
            log("no free cvat job left for ${request.pubkey.take(8)}…")
            return null
        }
        return publishGrant(request, escrowId, workspace, invitation.userId, invitation.key, job)
    }

    private fun assignFreeJob(workspace: CvatWorkspace, userId: Long): Long? {
        val free = org.jobs(workspace.taskId).firstOrNull { it.assigneeId == null } ?: return null
        org.assign(free.id, userId)
        return free.id
    }

    private fun publishGrant(
        request: NostrEvent,
        escrowId: String,
        workspace: CvatWorkspace,
        cvatId: Long,
        invitationKey: String,
        jobId: Long,
    ): NostrEvent {
        val event = CvatAccessCodec.grant(
            privkey,
            CvatAccessGrant(
                grantEventId = request.id,
                escrowId = escrowId,
                workerPubkey = request.pubkey,
                cvatId = cvatId,
                baseUrl = workspace.baseUrl,
                orgSlug = workspace.orgSlug,
                invitationKey = invitationKey,
            ),
            createdAt = System.currentTimeMillis() / 1000,
        )
        check(nostr.publish(event)) { "cvat access grant publish failed" }
        log("admitted ${request.pubkey.take(8)}… as cvat user $cvatId on job $jobId")
        return event
    }

    private companion object {
        const val FETCH_LIMIT = 500
    }
}
