package org.hpb.harness

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.random.RandomGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.cvat.CvatHttp
import org.hpb.cvat.MockCvat

/**
 * Shared plumbing for the tests that run against a *real* CVAT deployment:
 * frame upload (CVAT wants multipart, which the JDK client cannot encode),
 * the asynchronous import wait, and worker self-registration.
 *
 * Enabled only when CVAT_URL and CVAT_TOKEN are set; see deploy/cvat/README.md.
 */
object RealCvat {
    val baseUrl: String? = System.getenv("CVAT_URL")?.trimEnd('/')
    val token: String? = System.getenv("CVAT_TOKEN")

    val enabled: Boolean get() = !baseUrl.isNullOrBlank() && !token.isNullOrBlank()

    fun http() = CvatHttp(baseUrl!!, token!!)

    fun unique(prefix: String) = "$prefix-${RandomGenerator.getDefault().nextLong(SUFFIX_BOUND)}"

    /** A worker's own CVAT account. The launcher never learns the password. */
    fun registerWorker(username: String, email: String, password: String): String {
        val body = """{"username":"$username","email":"$email",""" +
            """"password1":"$password","password2":"$password"}"""
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = plain.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_CREATED) {
            "register -> HTTP ${response.statusCode()}: ${response.body().take(SNIPPET)}"
        }
        return Json.parseToJsonElement(response.body())
            .jsonObject.getValue("key").jsonPrimitive.content
    }

    /** Test hygiene only — a launcher revokes membership, it never deletes accounts. */
    fun deleteUser(id: Long) {
        http().exchange("DELETE", "/api/users/$id", null)
    }

    fun uploadFrames(taskId: Long, count: Int = MockCvat.FRAMES.size) {
        val parts = Multipart()
        parts.field("image_quality", "70")
        parts.field("sorting_method", "lexicographical")
        (0 until count).forEach { parts.file("client_files[$it]", "f$it.png", MockCvat.framePng(it)) }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/tasks/$taskId/data"))
            .header("Authorization", "Token $token")
            .header("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            .POST(HttpRequest.BodyPublishers.ofByteArray(parts.finish()))
            .build()
        val response = plain.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_ACCEPTED) {
            "frame upload -> HTTP ${response.statusCode()}: ${response.body().take(SNIPPET)}"
        }
    }

    /** Frames are unfetchable, and jobs do not exist, until the import finishes. */
    fun awaitDataReady(taskId: Long) {
        val rq = "action%3Dcreate%26target%3Dtask%26target_id%3D$taskId"
        repeat(POLL_ATTEMPTS) {
            val status = http().get("/api/requests/$rq").jsonObject
                .getValue("status").jsonPrimitive.content
            if (status == "finished") return
            check(status != "failed") { "cvat failed to import the frames for task $taskId" }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("cvat did not finish importing frames for task $taskId")
    }

    private val plain = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    /** Minimal multipart/form-data writer. */
    private class Multipart {
        private val out = ByteArrayOutputStream()

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

    private const val BOUNDARY = "hpbRealCvatBoundary"
    private const val HTTP_ACCEPTED = 202
    private const val HTTP_CREATED = 201
    private const val SNIPPET = 200
    private const val POLL_ATTEMPTS = 60
    private const val POLL_INTERVAL_MS = 2_000L
    private const val SUFFIX_BOUND = 1_000_000L
}
