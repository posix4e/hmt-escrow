package org.hpb.cvat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A job whose state changed, as CVAT reported it. */
data class CvatJobEvent(
    val jobId: Long,
    val state: String,
    val assigneeId: Long?,
    val updatedAt: String,
)

/**
 * Receives CVAT's `update:job` webhook.
 *
 * Two rules, both taken from how the production oracle does it, and both
 * load-bearing. The signature is checked before anything in the payload is
 * read, because the payload is attacker-controlled until then. And a delivery
 * is queued and acknowledged rather than acted on, so a slow or failing
 * consumer cannot make CVAT's webhook look broken; [drain] does the work.
 *
 * The queue is in memory: a restart forgets undelivered events, which is
 * acceptable only because a worker's submission — not this — is what actually
 * advances an assignment. Completion detection here is a prompt, not a source
 * of truth.
 */
class CvatWebhookListener(
    private val secret: String,
    port: Int = 0,
    bind: String = "127.0.0.1",
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(bind, port), 0)
    private val queue = ConcurrentLinkedQueue<CvatJobEvent>()

    val port: Int get() = server.address.port

    init {
        server.createContext("/cvat-webhook") { exchange -> exchange.use(::handle) }
        server.start()
    }

    /** Take everything received so far. Events are delivered once. */
    fun drain(): List<CvatJobEvent> = generateSequence { queue.poll() }.toList()

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes()
        if (!signatureMatches(exchange, body)) {
            log("rejected a webhook with a bad or missing signature")
            respond(exchange, HTTP_UNAUTHORIZED)
            return
        }
        runCatching { accept(body) }.onFailure { log("unreadable webhook: ${it.message}") }
        respond(exchange, HTTP_OK)
    }

    /** Constant-time, and before the body is parsed at all. */
    private fun signatureMatches(exchange: HttpExchange, body: ByteArray): Boolean {
        val offered = exchange.requestHeaders.getFirst("X-Signature-256") ?: return false
        return MessageDigest.isEqual(offered.toByteArray(), expected(body).toByteArray())
    }

    private fun expected(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /** Only a real state change is interesting; CVAT sends other updates too. */
    private fun accept(body: ByteArray) {
        val payload = Json.parseToJsonElement(body.decodeToString()).jsonObject
        if (payload["event"]?.jsonPrimitive?.content != UPDATE_JOB) return
        val before = payload["before_update"] as? JsonObject ?: return
        if ("state" !in before) return
        val job = payload["job"] as? JsonObject ?: return
        queue.add(
            CvatJobEvent(
                jobId = job.getValue("id").jsonPrimitive.long,
                state = job.getValue("state").jsonPrimitive.content,
                assigneeId = (job["assignee"] as? JsonObject)?.getValue("id")?.jsonPrimitive?.long,
                updatedAt = job["updated_date"]?.jsonPrimitive?.content.orEmpty(),
            ),
        )
    }

    private fun respond(exchange: HttpExchange, code: Int) {
        exchange.sendResponseHeaders(code, -1)
    }

    private companion object {
        const val UPDATE_JOB = "update:job"
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
    }
}
