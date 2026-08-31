package org.hpb.harness

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.hpb.cvat.CvatExchangeRole
import org.hpb.cvat.CvatOrg
import org.hpb.cvat.CvatWorkspace
import org.hpb.engine.Secp
import org.hpb.engine.nostr.NostrClient
import org.hpb.protocol.CvatAccessCodec
import org.hpb.protocol.CvatAccessRequest
import org.hpb.protocol.ExternalWork
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The launcher admitting a real worker to real CVAT over a real relay: the
 * handshake that replaces copying frames into offers.
 *
 * The worker here holds its own Nostr key and its own CVAT account, and the
 * launcher never learns its password — that separation is the whole point, so
 * it is exercised rather than asserted.
 */
class RealCvatExchangeTest {
    private val launcherKey = randomKey()
    private val workerKey = randomKey()

    @Test
    fun `the launcher admits a worker to its cvat org`() {
        assumeTrue(RealCvat.enabled && RealCvat.relays != null, "CVAT_URL/CVAT_TOKEN/HPB_RELAYS unset")
        val org = CvatOrg(RealCvat.http())
        val slug = RealCvat.unique("hpb-x")
        NostrClient(RealCvat.relayList()).use { nostr ->
            val exchange = CvatExchangeRole(org, nostr, launcherKey, RealCvat.baseUrl!!) {}
            val workspace = exchange.provision(slug, LABELS, RealCvat.shapeFrames())
            val strays = mutableListOf<Long>()
            try {
                exerciseHandshake(exchange, org, workspace, nostr, strays)
            } finally {
                org.deleteProject(workspace.projectId)
                org.deleteOrganization(workspace.orgId)
                strays.forEach(RealCvat::deleteUser)
            }
        }
    }

    private fun exerciseHandshake(
        exchange: CvatExchangeRole,
        org: CvatOrg,
        workspace: CvatWorkspace,
        nostr: NostrClient,
        strays: MutableList<Long>,
    ) {
        val escrowId = "escrow-${workspace.taskId}"
        val tasks = exchange.tasks(workspace)
        assertTrue(tasks.isNotEmpty(), "provisioning produced no protocol tasks")

        val work = assertNotNull(ExternalWork.workSource(tasks.first().question), "task carries no work source")
        assertEquals(workspace.taskId, work.taskId)
        assertTrue(work.url.startsWith(RealCvat.baseUrl!!), "work url does not point at this cvat")

        val worker = admitWorker(exchange, org, workspace, nostr, escrowId, strays)
        assertSybilRefused(exchange, workspace, nostr, escrowId, worker.email)
    }

    private fun admitWorker(
        exchange: CvatExchangeRole,
        org: CvatOrg,
        workspace: CvatWorkspace,
        nostr: NostrClient,
        escrowId: String,
        strays: MutableList<Long>,
    ): Worker {
        val username = RealCvat.unique("hpbw")
        val email = "$username@localhost.invalid"
        val token = RealCvat.registerWorker(username, email, WORKER_PASSWORD)
        // Recorded before anything that can fail, so a mid-test abort still cleans up.
        strays += RealCvat.selfId(token)

        publishRequest(nostr, workerKey, escrowId, email)
        val grants = exchange.serveAccessRequests(escrowId, workspace)
        assertEquals(1, grants.size, "expected exactly one access grant")

        // The worker decrypts its own invitation key and joins with its own account.
        val grant = CvatAccessCodec.parseGrant(grants.first(), workerKey)
        assertEquals(Secp.xonlyHex(workerKey), grant.workerPubkey)
        assertTrue(grant.invitationKey.isNotBlank(), "grant carried no invitation key")
        RealCvat.acceptInvitation(token, grant.invitationKey)

        val membership = org.memberships(workspace.orgSlug).firstOrNull { it.username == username }
        assertNotNull(membership, "worker never joined the org")
        assertTrue(membership.active, "membership inactive after accept")

        val assigned = org.jobs(workspace.taskId).count { it.assigneeId == grant.cvatId }
        assertEquals(1, assigned, "worker was not assigned exactly one cvat job")
        return Worker(email, grant.cvatId)
    }

    /** A second Nostr key naming the same CVAT account must not be admitted. */
    private fun assertSybilRefused(
        exchange: CvatExchangeRole,
        workspace: CvatWorkspace,
        nostr: NostrClient,
        escrowId: String,
        email: String,
    ) {
        publishRequest(nostr, randomKey(), escrowId, email)
        val grants = exchange.serveAccessRequests(escrowId, workspace)
        assertTrue(grants.isEmpty(), "a second worker was admitted onto the same cvat account")
    }

    private fun publishRequest(nostr: NostrClient, key: ByteArray, escrowId: String, email: String) {
        val event = CvatAccessCodec.request(
            key,
            Secp.xonlyHex(launcherKey),
            CvatAccessRequest("claim-$escrowId", escrowId, email),
            createdAt = System.currentTimeMillis() / 1000,
        )
        check(nostr.publish(event)) { "access request publish failed" }
    }

    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private data class Worker(val email: String, val cvatId: Long)

    private companion object {
        const val WORKER_PASSWORD = "qN7vzLd2Wm-Rk9tf"
        val LABELS = listOf("circle", "square", "triangle")
    }
}
