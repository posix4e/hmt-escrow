package org.hpb.harness

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.random.RandomGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.hpb.cvat.CvatHttp
import org.hpb.cvat.CvatOrg
import org.hpb.cvat.DemoFrames
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

    val relays: String? = System.getenv("HPB_RELAYS")?.takeIf { it.isNotBlank() }

    val enabled: Boolean get() = !baseUrl.isNullOrBlank() && !token.isNullOrBlank()

    fun relayList(): List<String> = relays.orEmpty().split(",").map(String::trim).filter { it.isNotEmpty() }

    /** Drawn shapes with known labels, so groundtruth validation is meaningful. */
    fun shapeFrames(): List<Pair<String, ByteArray>> = DemoFrames.frames()

    /**
     * The worker joins with its *own* token; the launcher never has it.
     *
     * Idempotent: when the invited address already belongs to a registered
     * account CVAT accepts the invitation on creation, so accepting again is a
     * 400 "already accepted" rather than a failure.
     */
    fun acceptInvitation(workerToken: String, key: String) {
        val response = CvatHttp(baseUrl!!, workerToken)
            .exchange("POST", "/api/invitations/$key/accept", "{}")
        val body = response.body().decodeToString()
        check(response.statusCode() in 200..299 || "already accepted" in body) {
            "accept -> HTTP ${response.statusCode()}: ${body.take(SNIPPET)}"
        }
    }

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

    /** Who a token belongs to, so a test can record cleanup before it can fail. */
    fun selfId(workerToken: String): Long =
        CvatHttp(baseUrl!!, workerToken).get("/api/users/self")
            .jsonObject.getValue("id").jsonPrimitive.long

    /** The worker annotates with its *own* token, as it would in the CVAT UI. */
    fun annotateJob(workerToken: String, jobId: Long, tags: List<Pair<Int, Long>>) {
        val rows = tags.joinToString(",") { (frame, labelId) ->
            """{"frame":$frame,"label_id":$labelId,"group":0,"source":"manual","attributes":[]}"""
        }
        CvatHttp(baseUrl!!, workerToken).patch(
            "/api/jobs/$jobId/annotations/?action=create",
            """{"version":0,"tags":[$rows],"shapes":[],"tracks":[]}""",
        )
    }

    /** Test hygiene only — a launcher revokes membership, it never deletes accounts. */
    fun deleteUser(id: Long) {
        http().exchange("DELETE", "/api/users/$id", null)
    }

    /** Frame upload and the import wait are launcher operations; use those. */
    fun uploadFrames(taskId: Long, count: Int = MockCvat.FRAMES.size) {
        val frames = (0 until count).map { "f$it.png" to MockCvat.framePng(it) }
        CvatOrg(http()).uploadFrames(taskId, frames)
    }

    fun awaitDataReady(taskId: Long) = CvatOrg(http()).awaitDataReady(taskId)

    private val plain = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private const val HTTP_CREATED = 201
    private const val SNIPPET = 200
    private const val SUFFIX_BOUND = 1_000_000L
}
