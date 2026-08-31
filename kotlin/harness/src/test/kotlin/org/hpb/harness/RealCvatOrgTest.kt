package org.hpb.harness

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.cvat.CvatAdmission
import org.hpb.cvat.CvatHttp
import org.hpb.cvat.CvatOrg
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The launcher's whole CVAT lifecycle against a real deployment: own an org,
 * publish work into it, lease a worker membership, hand out a job and take it
 * back, then revoke.
 *
 * The worker here is a genuinely separate account that registers itself and
 * accepts its own invitation — the property the design depends on, so it is
 * exercised rather than assumed.
 */
class RealCvatOrgTest {
    @Test
    fun `launcher runs the cvat lifecycle`() {
        assumeTrue(RealCvat.enabled, "CVAT_URL/CVAT_TOKEN unset")
        val org = CvatOrg(RealCvat.http())
        val slug = RealCvat.unique("hpb-org")
        val orgId = org.createOrganization(slug, "hpb test org")
        val strays = mutableListOf<Long>()
        try {
            exerciseLifecycle(org, slug, strays)
        } finally {
            org.deleteOrganization(orgId)
            strays.forEach(RealCvat::deleteUser)
        }
    }

    private fun exerciseLifecycle(org: CvatOrg, slug: String, strays: MutableList<Long>) {
        val projectId = org.createProject(slug, "hpb project", LABELS)
        try {
            exerciseProject(org, slug, projectId, strays)
        } finally {
            org.deleteProject(projectId)
        }
    }

    private fun exerciseProject(org: CvatOrg, slug: String, projectId: Long, strays: MutableList<Long>) {
        val taskId = org.createTask(slug, projectId, "hpb task")
        RealCvat.uploadFrames(taskId)
        RealCvat.awaitDataReady(taskId)

        val webhookId = org.registerWebhook(slug, projectId, "http://127.0.0.1:9/hook", "s3cr3t")
        assertTrue(webhookId > 0, "webhook was not created")

        val worker = admitWorker(org, slug, strays)
        val job = assertNotNull(org.jobs(taskId).firstOrNull(), "task produced no jobs")

        org.assign(job.id, worker.cvatId)
        assertEquals(worker.cvatId, org.jobs(taskId).first().assigneeId, "job was not assigned")

        org.assign(job.id, null)
        assertNull(org.jobs(taskId).first().assigneeId, "job was not unassigned")

        revoke(org, slug, worker.cvatId)
    }

    /** Invite, self-register, accept — the flow that keeps credentials with the worker. */
    private fun admitWorker(org: CvatOrg, slug: String, strays: MutableList<Long>): Worker {
        val username = RealCvat.unique("hpbworker")
        val email = "$username@localhost.invalid"

        // Tolerates the HTTP 500 CVAT returns with no mail server configured.
        val admission = org.invite(slug, email)
        val invitation = assertIs<CvatAdmission.Invited>(admission, "invite was refused").invitation
        strays += invitation.userId
        assertEquals(email, invitation.username, "invitation resolved to the wrong account")
        assertTrue(invitation.key.isNotBlank(), "invitation carried no key")

        val workerToken = RealCvat.registerWorker(username, email, WORKER_PASSWORD)
        assertEquals(slug, acceptAs(workerToken, invitation.key), "accept joined the wrong org")

        val membership = org.memberships(slug).firstOrNull { it.username == username }
        assertNotNull(membership, "no membership after accept")
        assertTrue(membership.active, "membership is still inactive after accept")
        assertEquals("worker", membership.role, "membership has the wrong role")
        strays += membership.userId
        return Worker(membership.userId, membership.id)
    }

    /** The worker accepts with *its own* token; anonymous accept is refused. */
    private fun acceptAs(workerToken: String, key: String): String {
        val anonymous = CvatHttp(RealCvat.baseUrl!!, "not-a-real-token")
            .exchange("POST", "/api/invitations/$key/accept", null)
        assertEquals(HTTP_UNAUTHORIZED, anonymous.statusCode(), "anonymous accept should be refused")

        return CvatHttp(RealCvat.baseUrl, workerToken)
            .post("/api/invitations/$key/accept", "{}")
            .jsonObject.getValue("organization_slug").jsonPrimitive.content
    }

    private fun revoke(org: CvatOrg, slug: String, cvatId: Long) {
        val membership = org.memberships(slug).first { it.userId == cvatId }
        org.removeMembership(membership.id)
        assertNull(
            org.memberships(slug).firstOrNull { it.userId == cvatId },
            "membership survived revocation",
        )
    }

    private data class Worker(val cvatId: Long, val membershipId: Long)

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val WORKER_PASSWORD = "qN7vzLd2Wm-Rk9tf"
        val LABELS = listOf("cat", "dog", "other")
    }
}
