package org.hpb.harness

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.hpb.cvat.CvatAdmission
import org.hpb.cvat.CvatAssignment
import org.hpb.cvat.CvatClient
import org.hpb.cvat.CvatOrg
import org.hpb.cvat.CvatRecordingRole
import org.hpb.cvat.DemoFrames
import org.hpb.engine.Secp
import org.hpb.protocol.WorkCompletion
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * A worker annotates real CVAT with its own credentials; recorders read the
 * work back and validation runs over what they pulled, not over what the
 * worker claimed.
 *
 * The point being proved is that the commitment makes the substitution
 * checkable: a launcher cannot swap in an answer of its own, because the bytes
 * must hash to what the worker signed before the launcher saw them.
 */
class RealCvatRecordingTest {
    private val worker = Secp.xonlyHex(ByteArray(32).also { SecureRandom().nextBytes(it) })

    @Test
    fun `validation runs over annotations pulled from cvat`() {
        assumeTrue(RealCvat.enabled, "CVAT_URL/CVAT_TOKEN unset")
        val org = CvatOrg(RealCvat.http())
        val slug = RealCvat.unique("hpb-rec")
        val orgId = org.createOrganization(slug, "hpb recording")
        val strays = mutableListOf<Long>()
        var projectId = 0L
        try {
            projectId = org.createProject(slug, "hpb labeling", DemoFrames.LABELS)
            val taskId = org.createTask(slug, projectId, "hpb task")
            org.uploadFrames(taskId, DemoFrames.frames())
            org.awaitDataReady(taskId)
            exercise(org, slug, taskId, strays)
        } finally {
            if (projectId != 0L) org.deleteProject(projectId)
            org.deleteOrganization(orgId)
            strays.forEach(RealCvat::deleteUser)
        }
    }

    private fun exercise(org: CvatOrg, slug: String, taskId: Long, strays: MutableList<Long>) {
        val client = CvatClient(RealCvat.http())
        val labels = client.labels(taskId).associateBy { it.name }
        val jobId = org.jobs(taskId).first().id
        val taskKey = "cvat-job-$jobId"

        val token = admitAnnotator(org, slug, jobId, strays)
        val correct = DemoFrames.SHAPES.mapIndexed { frame, shape -> frame to labels.getValue(shape).id }
        RealCvat.annotateJob(token, jobId, correct)

        val recorder = CvatRecordingRole(client, client.labels(taskId).associate { it.id to it.name })
        val pulled = recorder.pullAll(listOf(CvatAssignment(worker, taskKey, jobId)))
        val canonical = pulled.getValue(worker to taskKey)
        val expected = ExternalWork.canonicalAnnotations(DemoFrames.SHAPES.mapIndexed { f, s -> f to s })
        assertEquals(expected, canonical, "recorder did not read back what the worker drew")

        assertHonestSubstitution(taskKey, canonical)
        assertTamperingIsRejected(taskKey, canonical)
        assertUncommittedWorkIsNotPaid(taskKey, canonical)
        assertGroundtruthPays(taskKey, canonical)
        assertRecordersMustAgree(recorder, jobId, taskKey, pulled)
    }

    /** The worker's public commitment matches, so the pulled answer stands in. */
    private fun assertHonestSubstitution(taskKey: String, canonical: String) {
        val submitted = listOf(Validators.Submitted(taskKey, worker, commitment(canonical)))
        val committed = mapOf((worker to taskKey) to ExternalWork.hashOf(canonical))
        val resolved = ExternalWork.substitute(submitted, mapOf((worker to taskKey) to canonical), committed)
        assertEquals(listOf(Validators.Submitted(taskKey, worker, canonical)), resolved)
    }

    /** With no public commitment there is nothing a witness could check. */
    private fun assertUncommittedWorkIsNotPaid(taskKey: String, canonical: String) {
        val submitted = listOf(Validators.Submitted(taskKey, worker, commitment(canonical)))
        val resolved = ExternalWork.substitute(submitted, mapOf((worker to taskKey) to canonical), emptyMap())
        assertTrue(resolved.isEmpty(), "work with no public commitment was accepted")
    }

    /** A launcher that edits CVAT after submission cannot get the edit paid. */
    private fun assertTamperingIsRejected(taskKey: String, canonical: String) {
        val submitted = listOf(Validators.Submitted(taskKey, worker, commitment(canonical)))
        val committed = mapOf((worker to taskKey) to ExternalWork.hashOf(canonical))
        val tampered = canonical.replace("circle", "square")
        assertTrue(tampered != canonical, "tamper fixture did not change anything")
        val resolved = ExternalWork.substitute(submitted, mapOf((worker to taskKey) to tampered), committed)
        assertTrue(resolved.isEmpty(), "annotations that do not match the commitment were accepted")
    }

    /** Correct labels are paid; the groundtruth is the frames the launcher drew. */
    private fun assertGroundtruthPays(taskKey: String, canonical: String) {
        val policy = ValidationPolicy(
            ValidationType.GROUNDTRUTH,
            groundtruthHashes = setOf(Validators.groundtruthHash(taskKey, canonical)),
        )
        val rows = Validators.validate(policy, listOf(Validators.Submitted(taskKey, worker, canonical)))
        assertTrue(rows.single().accepted, "correct annotations were not accepted")

        val wrong = listOf(Validators.Submitted(taskKey, worker, "0:square\n1:square"))
        assertTrue(!Validators.validate(policy, wrong).single().accepted, "wrong annotations were accepted")
    }

    /** Two recorders reading the same job agree; a divergent one contributes nothing. */
    private fun assertRecordersMustAgree(
        recorder: CvatRecordingRole,
        jobId: Long,
        taskKey: String,
        pulled: Map<Pair<String, String>, String>,
    ) {
        val second = recorder.pullAll(listOf(CvatAssignment(worker, taskKey, jobId)))
        assertEquals(pulled, CvatRecordingRole.agree(pulled, second), "identical pulls did not agree")

        val divergent = mapOf((worker to taskKey) to "0:square")
        assertTrue(
            CvatRecordingRole.agree(pulled, divergent).isEmpty(),
            "a disagreeing recorder was allowed to decide the answer",
        )
    }

    private fun admitAnnotator(org: CvatOrg, slug: String, jobId: Long, strays: MutableList<Long>): String {
        val username = RealCvat.unique("hpbr")
        val token = RealCvat.registerWorker(username, "$username@localhost.invalid", WORKER_PASSWORD)
        strays += RealCvat.selfId(token)
        val admission = org.invite(slug, "$username@localhost.invalid")
        val invitation = assertIs<CvatAdmission.Invited>(admission, "annotator was refused").invitation
        RealCvat.acceptInvitation(token, invitation.key)
        org.assign(jobId, invitation.userId)
        return token
    }

    private fun commitment(canonical: String) = ExternalWork.answer(
        WorkCompletion(ref = "1", resultSha256 = ExternalWork.hashOf(canonical)),
    )

    private companion object {
        const val WORKER_PASSWORD = "qN7vzLd2Wm-Rk9tf"
    }
}
