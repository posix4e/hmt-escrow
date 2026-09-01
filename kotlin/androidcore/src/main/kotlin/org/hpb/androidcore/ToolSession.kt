package org.hpb.androidcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.WorkSource

/**
 * The worker's own session in the tool that hosts the work.
 *
 * The credential here belongs to the worker and never leaves the device: the
 * launcher can assign work and read results, but it cannot annotate *as* this
 * worker, which is the property the whole design rests on.
 *
 * Deliberately cookie-free. This is a token client, and if it also carried
 * CVAT's `sessionid` then Django would authenticate the session instead and
 * reject every POST without an `X-CSRFToken`.
 */
class ToolSession(private val baseUrl: String, private val token: String) {
    private val http = OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()

    /** Who this credential belongs to, and the address a launcher invites. */
    data class Account(val id: Long, val username: String, val email: String)

    fun account(): Account {
        val me = get("/api/users/self").jsonObject
        return Account(
            id = me.getValue("id").jsonPrimitive.long,
            username = me.getValue("username").jsonPrimitive.content,
            email = me["email"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /**
     * Join the organization the launcher invited this account to.
     *
     * Idempotent: when the invited address already belonged to a registered
     * account CVAT accepts on creation, so accepting again is a 400 rather than
     * a failure.
     */
    fun acceptInvitation(key: String) {
        val response = send("POST", "/api/invitations/$key/accept", "{}")
        check(response.first in HTTP_OK || "already accepted" in response.second) {
            "accept -> HTTP ${response.first}: ${response.second.take(SNIPPET)}"
        }
    }

    /**
     * Read back what *this worker* drew, canonicalised for hashing.
     *
     * Labels are fetched by job, not task: a worker is granted the job it was
     * assigned, and asking by `task_id` is refused with 403.
     */
    fun canonicalResult(work: WorkSource): String {
        val unit = work.params["job_id"]?.toLongOrNull()
            ?: error("work source names no unit to read back")
        val labels = get("/api/labels?job_id=$unit&page_size=$PAGE").jsonObject
            .getValue("results").jsonArray.associate {
                it.jsonObject.getValue("id").jsonPrimitive.long to
                    it.jsonObject.getValue("name").jsonPrimitive.content
            }
        val tags = get("/api/jobs/$unit/annotations/").jsonObject
            .getValue("tags").jsonArray.map {
                it.jsonObject.getValue("frame").jsonPrimitive.int to
                    (labels[it.jsonObject.getValue("label_id").jsonPrimitive.long] ?: UNKNOWN_LABEL)
            }
        return ExternalWork.canonical(work.result, tags)
    }

    private fun get(path: String) = Json.parseToJsonElement(checked("GET", path, null))

    private fun checked(method: String, path: String, body: String?): String {
        val (code, text) = send(method, path, body)
        check(code in HTTP_OK) { "tool $method $path -> HTTP $code: ${text.take(SNIPPET)}" }
        return text
    }

    private fun send(method: String, path: String, body: String?): Pair<Int, String> {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Token $token")
            .method(method, body?.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string().orEmpty())
        }
    }

    companion object {
        const val UNKNOWN_LABEL = "unknown"

        /** Exchange a password for a token once; the password is never stored. */
        fun signIn(baseUrl: String, username: String, password: String): String {
            val client = OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
            val body = """{"username":"$username","password":"$password"}""".toRequestBody(JSON)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/auth/login").post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "sign-in -> HTTP ${response.code}: ${text.take(SNIPPET)}" }
                return Json.parseToJsonElement(text).jsonObject.getValue("key").jsonPrimitive.content
            }
        }

        private val JSON = "application/json".toMediaType()
        private val HTTP_OK = 200..299
        private const val SNIPPET = 200
        private const val PAGE = 100
    }
}
