package org.hpb.cvat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
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
 * The invitation exposes no `email` field, only the account it resolved to.
 * [username] is that account's name — the invited address when CVAT had to
 * create a placeholder, but the person's existing username when they had
 * already registered. Match on [userId], never on [username].
 */
data class CvatInvitation(val key: String, val username: String, val userId: Long, val accepted: Boolean)

/** A CVAT job — the unit of assignment one protocol task maps to. */
data class CvatJob(val id: Long, val state: String, val stage: String, val assigneeId: Long?)

/**
 * The outcome of admitting an address. CVAT exposes no way to resolve an email
 * to a user — `search` does not cover it and `filter` rejects the term — so a
 * launcher cannot look one up before inviting. It does not need to: membership
 * of a workspace org is only ever granted by admitting a worker, so CVAT's own
 * "already a member" is the signal that this account already backs someone.
 */
sealed interface CvatAdmission {
    data class Invited(val invitation: CvatInvitation) : CvatAdmission

    data object AlreadyMember : CvatAdmission
}

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
     * Invite [email] into the org as a worker.
     *
     * Three outcomes, all normal. When the address already belongs to a
     * registered account CVAT answers 201, the body *is* the invitation, and it
     * is already accepted. When it must create a placeholder and no SMTP is
     * configured it answers 500 "Email backend is not configured" — but only
     * after writing the invitation, the user and the membership, so the key is
     * still obtainable by listing. When the account is already in the org it
     * answers 400, which is a refusal, not a failure.
     */
    fun invite(slug: String, email: String): CvatAdmission {
        val response = http.exchange(
            "POST",
            "/api/invitations?org=$slug",
            """{"role":"worker","email":"${email.escaped()}"}""",
        )
        val body = response.body().decodeToString()
        if (response.statusCode() in HTTP_OK) {
            invitation(Json.parseToJsonElement(body))?.let { return CvatAdmission.Invited(it) }
        }
        if (ALREADY_MEMBER in body) return CvatAdmission.AlreadyMember
        // Placeholder path: the account CVAT just made is named after the address.
        val listed = invitations(slug).firstOrNull { it.username.equals(email, ignoreCase = true) }
        checkNotNull(listed) {
            "CVAT did not create an invitation for $email " +
                "(HTTP ${response.statusCode()}: ${body.take(SNIPPET)})"
        }
        return CvatAdmission.Invited(listed)
    }

    fun invitations(slug: String): List<CvatInvitation> =
        http.get("/api/invitations?org=$slug&page_size=$PAGE").results().mapNotNull(::invitation)

    private fun invitation(row: JsonElement): CvatInvitation? {
        val user = row.jsonObject["user"] as? JsonObject ?: return null
        return CvatInvitation(
            key = row.jsonObject.getValue("key").jsonPrimitive.content,
            username = user.getValue("username").jsonPrimitive.content,
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
        val HTTP_OK = 200..299
        const val ALREADY_MEMBER = "member of the organization already"
        const val PAGE = 100
        const val SNIPPET = 200
        const val POLL_ATTEMPTS = 60
        const val POLL_INTERVAL_MS = 2_000L
    }
}
