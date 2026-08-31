package org.hpb.harness

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.hpb.cvat.CvatClient
import org.hpb.cvat.CvatTag
import org.hpb.cvat.MockCvat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Pins [CvatClient] against a *real* CVAT server — the mock cannot answer
 * whether our API guesses are right, because the same author wrote both.
 *
 * Skipped unless CVAT_URL and CVAT_TOKEN are set; see deploy/cvat/README.md.
 * Creates a throwaway task, exercises every call the client makes, and
 * deletes the task again.
 */
class RealCvatClientTest {
    private val http get() = RealCvat.http()

    private fun requireDeployment() = assumeTrue(RealCvat.enabled, "CVAT_URL/CVAT_TOKEN unset")

    @Test
    fun `a bad token is rejected`() {
        requireDeployment()
        val wrong = CvatClient(RealCvat.baseUrl!!, "not-a-real-token")
        val failure = assertFailsWith<IllegalStateException> { wrong.taskName(1) }
        assertTrue("401" in failure.message.orEmpty(), "expected 401, got ${failure.message}")
    }

    @Test
    fun `a missing task is rejected`() {
        requireDeployment()
        val client = CvatClient(RealCvat.http())
        val failure = assertFailsWith<IllegalStateException> { client.taskName(MISSING_TASK_ID) }
        assertTrue("404" in failure.message.orEmpty(), "expected 404, got ${failure.message}")
    }

    @Test
    fun `client speaks the real cvat api`() {
        requireDeployment()
        val name = RealCvat.unique("hpb-realtest")
        val labels = LABELS.joinToString(",") { """{"name":"$it"}""" }
        val taskId = http.post("/api/tasks", """{"name":"$name","labels":[$labels]}""")
            .jsonObject.getValue("id").jsonPrimitive.long
        try {
            RealCvat.uploadFrames(taskId)
            RealCvat.awaitDataReady(taskId)
            verifyClient(taskId, name)
        } finally {
            http.delete("/api/tasks/$taskId")
        }
    }

    /** Every call [CvatClient] makes, against the server it will meet in production. */
    private fun verifyClient(taskId: Long, name: String) {
        val client = CvatClient(RealCvat.http())
        assertEquals(name, client.taskName(taskId), "taskName")
        assertEquals(LABELS, client.labels(taskId).map { it.name }, "labels")
        assertEquals(MockCvat.FRAMES.size, client.frameCount(taskId), "frameCount")

        val frame = client.frame(taskId, 0)
        assertTrue(frame.bytes.isNotEmpty(), "frame 0 came back empty")
        assertTrue(
            frame.contentType.startsWith("image/jpeg"),
            "quality=compressed serves JPEG; got ${frame.contentType}",
        )
        assertContentEquals(
            JPEG_MAGIC,
            frame.bytes.take(JPEG_MAGIC.size).toByteArray(),
            "frame bytes do not match the declared media type",
        )

        val labelId = client.labels(taskId).first().id
        client.appendTags(taskId, listOf(CvatTag(frame = 0, labelId = labelId)))
        assertEquals(listOf(CvatTag(0, labelId)), client.tags(taskId), "tag did not persist")
    }

    private companion object {
        const val MISSING_TASK_ID = 999_999_999L
        val LABELS = listOf("cat", "dog", "other")
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }
}
