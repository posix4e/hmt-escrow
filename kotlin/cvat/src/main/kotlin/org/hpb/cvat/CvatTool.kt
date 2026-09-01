package org.hpb.cvat

import java.time.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.WorkSource
import org.hpb.protocol.WorkSurface

/** A credential handed to whoever will actually do the work. */
data class DelegatedCredential(val id: Long, val token: String, val expiresAt: Instant)

/**
 * What a launcher needs from any tool that can host work.
 *
 * Only three things are genuinely tool-shaped — getting work into the tool,
 * admitting a worker to it, and reading results back. Everything around them
 * (polling for access requests, deduplicating identities, running two recorders
 * and comparing them, substituting verified results) is already tool-agnostic
 * and lives in the roles.
 *
 * [mintDelegated] is the seam that keeps a worker's own credential on its own
 * device: when work is routed to a desktop or an agent, the control plane hands
 * over something narrow and expiring instead of the credential it holds. A tool
 * with no delegation primitive returns null, and the caller must then decide
 * whether forwarding the real credential is acceptable.
 */
interface WorkTool {
    val id: String

    fun admit(accountRef: String): CvatAdmission

    fun mintDelegated(name: String, expiresAt: Instant): DelegatedCredential?

    fun revokeDelegated(id: Long)

    fun pull(unitId: Long): String
}

/**
 * CVAT as a work tool.
 *
 * CVAT has no OAuth — its API offers only password login and access tokens — so
 * delegation is done with `/api/auth/access_tokens`, which mints a named token
 * with an expiry that can be listed and revoked. Note the scoping is expiry plus
 * revocation, not reduced permission: `read_only` exists but is useless for an
 * agent that has to write annotations.
 */
class CvatTool(
    private val org: CvatOrg,
    private val client: CvatClient,
    private val orgSlug: String,
    private val labelsById: Map<Long, String>,
) : WorkTool {
    override val id: String = ExternalWork.TOOL_CVAT

    override fun admit(accountRef: String): CvatAdmission = org.invite(orgSlug, accountRef)

    override fun mintDelegated(name: String, expiresAt: Instant): DelegatedCredential {
        val body = """{"name":"${name.take(NAME_LIMIT)}","expiry_date":"$expiresAt","read_only":false}"""
        val created = org.http.post("/api/auth/access_tokens", body).jsonObject
        return DelegatedCredential(
            id = created.getValue("id").jsonPrimitive.long,
            token = created.getValue("value").jsonPrimitive.content,
            expiresAt = expiresAt,
        )
    }

    override fun revokeDelegated(id: Long) = org.http.delete("/api/auth/access_tokens/$id")

    override fun pull(unitId: Long): String =
        ExternalWork.canonical(
            WorkSource.RESULT_TAGS,
            client.jobTags(unitId).map { it.frame to (labelsById[it.labelId] ?: UNKNOWN_LABEL) },
        )

    companion object {
        const val UNKNOWN_LABEL = "unknown"
        private const val NAME_LIMIT = 50

        /**
         * CVAT's editor assumes a desktop, so work is advertised as such and a
         * phone routes it onward rather than trying to be the surface.
         */
        fun workSource(workspace: CvatWorkspace, jobId: Long) = WorkSource(
            tool = ExternalWork.TOOL_CVAT,
            url = "${workspace.baseUrl.trimEnd('/')}/tasks/${workspace.taskId}/jobs/$jobId",
            surface = WorkSurface.DESKTOP,
            result = WorkSource.RESULT_TAGS,
            params = mapOf(
                "org" to workspace.orgSlug,
                "task_id" to workspace.taskId.toString(),
                "job_id" to jobId.toString(),
                "labels" to workspace.labels.joinToString(","),
            ),
        )

        fun unitId(source: WorkSource): Long? = source.params["job_id"]?.toLongOrNull()
    }
}
