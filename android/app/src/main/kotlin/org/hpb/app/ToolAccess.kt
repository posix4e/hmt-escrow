package org.hpb.app

import org.hpb.androidcore.ToolSession
import org.hpb.androidcore.WorkerSession

/**
 * This worker's sessions in the tools that host its work.
 *
 * The credential for each tool stays in the vault on this device. The launcher
 * learns only the address it should invite — never the credential — which is
 * what stops it annotating as this worker and calling the result theirs.
 */
class ToolAccess(private val vault: Vault, private val baseUrls: Map<String, String>) {

    fun signedIn(tool: String): Boolean = vault.toolCredential(tool) != null

    fun session(tool: String): ToolSession {
        val token = requireNotNull(vault.toolCredential(tool)) { "not signed in to $tool" }
        val base = requireNotNull(baseUrls[tool]) { "no address configured for $tool" }
        return ToolSession(base, token)
    }

    /**
     * Exchange a password for a token once, then keep only the token.
     *
     * Returns the address the launcher will invite, taken from the tool itself
     * rather than typed again — an address that does not match the account would
     * be admitted to nothing.
     */
    fun signIn(tool: String, baseUrl: String, username: String, password: String): String {
        val token = ToolSession.signIn(baseUrl, username, password)
        vault.storeToolCredential(tool, token)
        return ToolSession(baseUrl, token).account().email
    }

    fun signOut(tool: String) = vault.clearToolCredential(tool)

    /**
     * Tell the network which tools this worker can verify results for.
     *
     * Declared only for tools it is actually signed into: claiming work it
     * cannot finish is what strands a job.
     */
    fun declare(worker: WorkerSession) {
        runCatching { worker.declareTools(vault.tools()) }
    }
}
