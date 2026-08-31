package org.hpb.cvat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * An outstanding invitation. [key] is the credential the worker needs.
 *
 * CVAT creates a placeholder account named after the invited address and
 * exposes no `email` field on the invitation, so [email] is read from that
 * account's username.
 */
data class CvatInvitation(val key: String, val email: String, val userId: Long, val accepted: Boolean)

/** A CVAT job — the unit of assignment one protocol task maps to. */
data class CvatJob(val id: Long, val state: String, val stage: String, val assigneeId: Long?)

data class CvatMembership(val id: Long, val userId: Long, val username: String, val role: String, val active: Boolean)

/**
 * The launcher's side of CVAT: own an organization, publish work into it, lease
 * workers membership, hand out jobs and take them back.
 *
 * Deliberately separate from [CvatClient], which is the worker-facing slice.
 * Nothing here mints or holds worker credentials — a worker registers their own
 * CVAT account and accepts an invitation with it, so the launcher can never
 * author annotations as a worker.
 */
class CvatOrg(private val http: CvatHttp) {
    constructor(baseUrl: String, token: String) : this(CvatHttp(baseUrl, token))

    fun createOrganization(slug: String, name: String): Long =
        http.post("/api/organizations", """{"slug":"$slug","name":"$name"}""").id()

    fun deleteOrganization(id: Long) = http.delete("/api/organizations/$id")

    fun createProject(slug: String, name: String, labels: List<String>): Long {
        val labelJson = labels.joinToString(",") { """{"name":"${it.escaped()}"}""" }
        return http.post(
            "/api/projects?org=$slug",
            """{"name":"${name.escaped()}","labels":[$labelJson]}""",
        ).id()
    }

    /** Deleting a project takes its tasks and jobs with it. */
    fun deleteProject(id: Long) = http.delete("/api/projects/$id")

    fun createTask(slug: String, projectId: Long, name: String): Long =
        http.post(
            "/api/tasks?org=$slug",
            """{"name":"${name.escaped()}","project_id":$projectId}""",
        ).id()

    /** CVAT wants the frames as multipart, and imports them asynchronously. */
    fun uploadFrames(taskId: Long, frames: List<Pair<String, ByteArray>>) {
        val parts = Multipart()
        parts.field("image_quality", "70")
        parts.field("sorting_method", "lexicographical")
        frames.forEachIndexed { index, (name, bytes) -> parts.file("client_files[$index]", name, bytes) }
        http.postBytes("/api/tasks/$taskId/data", parts.body(), parts.contentType)
    }

    /**
     * Frames are unfetchable — and the task has no jobs at all — until CVAT
     * finishes importing them, so provisioning has to wait here.
     */
    fun awaitDataReady(taskId: Long, attempts: Int = POLL_ATTEMPTS, intervalMs: Long = POLL_INTERVAL_MS) {
        val rq = "action%3Dcreate%26target%3Dtask%26target_id%3D$taskId"
        repeat(attempts) {
            val status = http.get("/api/requests/$rq").jsonObject
                .getValue("status").jsonPrimitive.content
            if (status == "finished") return
            check(status != "failed") { "cvat failed to import frames for task $taskId" }
            Thread.sleep(intervalMs)
        }
        error("cvat did not finish importing frames for task $taskId")
    }

    /**
     * One project-scoped webhook on `update:job`, matching what production
     * registers. [secret] signs deliveries as `X-Signature-256`; verify it
     * before trusting anything in the payload.
     */
    fun registerWebhook(slug: String, projectId: Long, targetUrl: String, secret: String): Long =
        http.post(
            "/api/webhooks?org=$slug",
            JsonObject(
                mapOf(
                    "target_url" to JsonPrimitive(targetUrl),
                    "type" to JsonPrimitive("project"),
                    "project_id" to JsonPrimitive(projectId),
                    "content_type" to JsonPrimitive("application/json"),
                    "secret" to JsonPrimitive(secret),
                    "is_active" to JsonPrimitive(true),
                    "events" to JsonArray(listOf(JsonPrimitive("update:job"))),
                ),
            ).toString(),
        ).id()

    /**
     * Invite [email] into the org as a worker and return the invitation.
     *
     * CVAT answers HTTP 500 "Email backend is not configured" on a deployment
     * without SMTP — *after* writing the invitation, the user and the
     * membership. The key is therefore still obtainable, so the outcome is
     * decided by whether the invitation exists, not by the status code.
     */
    fun invite(slug: String, email: String): CvatInvitation {
        val response = http.exchange(
            "POST",
            "/api/invitations?org=$slug",
            """{"role":"worker","email":"${email.escaped()}"}""",
        )
        return invitations(slug).firstOrNull { it.email.equals(email, ignoreCase = true) }
            ?: error(
                "CVAT did not create an invitation for $email " +
                    "(HTTP ${response.statusCode()}: ${response.body().decodeToString().take(SNIPPET)})",
            )
    }

    fun invitations(slug: String): List<CvatInvitation> =
        http.get("/api/invitations?org=$slug&page_size=$PAGE").results().mapNotNull { row ->
            val user = row.jsonObject["user"] as? JsonObject ?: return@mapNotNull null
            CvatInvitation(
                key = row.jsonObject.getValue("key").jsonPrimitive.content,
                email = user.getValue("username").jsonPrimitive.content,
                userId = user.getValue("id").jsonPrimitive.long,
                accepted = row.jsonObject.getValue("accepted").jsonPrimitive.boolean,
            )
        }

    fun jobs(taskId: Long): List<CvatJob> =
        http.get("/api/jobs?task_id=$taskId&page_size=$PAGE").results().map { row ->
            CvatJob(
                id = row.jsonObject.getValue("id").jsonPrimitive.long,
                state = row.jsonObject.getValue("state").jsonPrimitive.content,
                stage = row.jsonObject.getValue("stage").jsonPrimitive.content,
                assigneeId = (row.jsonObject["assignee"] as? JsonObject)
                    ?.getValue("id")?.jsonPrimitive?.long,
            )
        }

    /** Hand a job to a worker, or take it back with a null [userId]. */
    fun assign(jobId: Long, userId: Long?) {
        http.patch("/api/jobs/$jobId", """{"assignee":${userId ?: "null"}}""")
    }

    fun memberships(slug: String): List<CvatMembership> =
        http.get("/api/memberships?org=$slug&page_size=$PAGE").results().map { row ->
            val user = row.jsonObject.getValue("user").jsonObject
            CvatMembership(
                id = row.jsonObject.getValue("id").jsonPrimitive.long,
                userId = user.getValue("id").jsonPrimitive.long,
                username = user.getValue("username").jsonPrimitive.content,
                role = row.jsonObject.getValue("role").jsonPrimitive.content,
                active = row.jsonObject.getValue("is_active").jsonPrimitive.boolean,
            )
        }

    /** Revoke access by ending the membership — never by deleting the account. */
    fun removeMembership(id: Long) = http.delete("/api/memberships/$id")

    private fun JsonElement.id(): Long = jsonObject.getValue("id").jsonPrimitive.long

    private fun JsonElement.results() = jsonObject.getValue("results").jsonArray

    private fun String.escaped() = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val PAGE = 100
        const val SNIPPET = 200
        const val POLL_ATTEMPTS = 60
        const val POLL_INTERVAL_MS = 2_000L
    }
}
