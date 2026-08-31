package org.hpb.harness

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.random.RandomGenerator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
    private val baseUrl = System.getenv("CVAT_URL")?.trimEnd('/')
    private val token = System.getenv("CVAT_TOKEN")
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun requireDeployment() =
        assumeTrue(!baseUrl.isNullOrBlank() && !token.isNullOrBlank(), "CVAT_URL/CVAT_TOKEN unset")

    @Test
    fun `a bad token is rejected`() {
        requireDeployment()
        val wrong = CvatClient(baseUrl!!, "not-a-real-token")
        val failure = assertFailsWith<IllegalStateException> { wrong.taskName(1) }
        assertTrue("401" in failure.message.orEmpty(), "expected 401, got ${failure.message}")
    }

    @Test
    fun `a missing task is rejected`() {
        requireDeployment()
        val client = CvatClient(baseUrl!!, token!!)
        val failure = assertFailsWith<IllegalStateException> { client.taskName(MISSING_TASK_ID) }
        assertTrue("404" in failure.message.orEmpty(), "expected 404, got ${failure.message}")
    }

    @Test
    fun `client speaks the real cvat api`() {
        requireDeployment()
        val taskId = createTask()
        try {
            uploadFrames(taskId)
            awaitDataReady(taskId)
            verifyClient(taskId)
        } finally {
            send("DELETE", "/api/tasks/$taskId", null)
        }
    }

    /** Every call [CvatClient] makes, against the server it will meet in production. */
    private fun verifyClient(taskId: Long) {
        val client = CvatClient(baseUrl!!, token!!)
        assertEquals(taskName(taskId), client.taskName(taskId), "taskName")
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
        assertEquals(listOf(0 to labelId), storedTags(taskId), "tag did not persist")
    }

    private fun taskName(taskId: Long): String =
        json("/api/tasks/$taskId").jsonObject.getValue("name").jsonPrimitive.content

    private fun storedTags(taskId: Long): List<Pair<Int, Long>> =
        json("/api/tasks/$taskId/annotations").jsonObject.getValue("tags").jsonArray.map {
            it.jsonObject.getValue("frame").jsonPrimitive.int to
                it.jsonObject.getValue("label_id").jsonPrimitive.long
        }

    private fun createTask(): Long {
        val suffix = RandomGenerator.getDefault().nextLong(SUFFIX_BOUND)
        val labels = LABELS.joinToString(",") { """{"name":"$it"}""" }
        val body = """{"name":"hpb-realtest-$suffix","labels":[$labels]}"""
        val created = Json.parseToJsonElement(send("POST", "/api/tasks", body).decodeToString())
        return created.jsonObject.getValue("id").jsonPrimitive.long
    }

    /** CVAT takes the frames as multipart; the JDK client has no encoder of its own. */
    private fun uploadFrames(taskId: Long) {
        val parts = ByteArrayBuilder()
        parts.field("image_quality", "70")
        parts.field("sorting_method", "lexicographical")
        MockCvat.FRAMES.indices.forEach {
            parts.file("client_files[$it]", "f$it.png", MockCvat.framePng(it))
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/tasks/$taskId/data"))
            .header("Authorization", "Token $token")
            .header("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            .POST(HttpRequest.BodyPublishers.ofByteArray(parts.finish()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_ACCEPTED) {
            "frame upload -> HTTP ${response.statusCode()}: ${response.body().take(BODY_SNIPPET)}"
        }
    }

    /** Frames are unfetchable until the import job finishes. */
    private fun awaitDataReady(taskId: Long) {
        val rqId = "action=create&target=task&target_id=$taskId"
        repeat(POLL_ATTEMPTS) {
            val status = json("/api/requests/${encode(rqId)}")
                .jsonObject.getValue("status").jsonPrimitive.content
            if (status == "finished") return
            check(status != "failed") { "cvat failed to import the frames" }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("cvat did not finish importing frames for task $taskId")
    }

    private fun encode(raw: String) = raw.replace("=", "%3D").replace("&", "%26")

    private fun json(path: String) = Json.parseToJsonElement(send("GET", path, null).decodeToString())

    private fun send(method: String, path: String, body: String?): ByteArray {
        val publisher = if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body)
        }
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Token $token")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in HTTP_OK_RANGE) {
            "$method $path -> HTTP ${response.statusCode()}: " +
                response.body().decodeToString().take(BODY_SNIPPET)
        }
        return response.body()
    }

    /** Minimal multipart/form-data writer. */
    private class ByteArrayBuilder {
        private val out = java.io.ByteArrayOutputStream()

        fun field(name: String, value: String) {
            out.write("--$BOUNDARY\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write("$value\r\n".toByteArray())
        }

        fun file(name: String, filename: String, bytes: ByteArray) {
            out.write("--$BOUNDARY\r\n".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n".toByteArray(),
            )
            out.write("Content-Type: image/png\r\n\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
        }

        fun finish(): ByteArray {
            out.write("--$BOUNDARY--\r\n".toByteArray())
            return out.toByteArray()
        }
    }

    private companion object {
        const val BOUNDARY = "hpbRealCvatTestBoundary"
        const val HTTP_ACCEPTED = 202
        const val BODY_SNIPPET = 200
        const val POLL_ATTEMPTS = 60
        const val POLL_INTERVAL_MS = 2_000L
        const val SUFFIX_BOUND = 1_000_000L
        const val MISSING_TASK_ID = 999_999_999L
        val HTTP_OK_RANGE = 200..299
        val LABELS = listOf("cat", "dog", "other")
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }
}
