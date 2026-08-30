package org.hpb.labeler

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.androidcore.OkRelayClient
import org.hpb.androidcore.WorkerSession
import org.hpb.engine.hex
import org.hpb.engine.hexBytes
import org.hpb.protocol.Answer
import org.hpb.protocol.AssignmentStatus

/**
 * The miniature labeling app: a localhost page over [WorkerSession] — the
 * worker's whole world is still just a key and relays; this process holds
 * the key and serves the UI, nothing else. Claim, label, submit, get paid.
 */
class LabelerApp(
    private val worker: WorkerSession,
    port: Int = 0,
    private val network: String = "",
) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val jobs = ConcurrentHashMap<String, WorkerSession.JobRow>()

    val port: Int get() = server.address.port
    val url: String get() = "http://127.0.0.1:$port/"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { handle(it) { _ -> page() } }
        server.createContext("/api/state") { handle(it) { _ -> state() } }
        server.createContext("/api/claim") { handle(it, mutating = true, ::claim) }
        server.createContext("/api/submit") { handle(it, mutating = true, ::submit) }
    }

    fun start() = server.start()

    fun stop() = server.stop(0)

    private fun handle(
        exchange: HttpExchange,
        mutating: Boolean = false,
        action: (JsonObject) -> Response,
    ) {
        val response = runCatching {
            if (mutating) checkSameOrigin(exchange)
            val body = exchange.requestBody.readBytes().decodeToString()
            action(if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject)
        }.getOrElse { Response(500, it.message ?: "error", "text/plain") }
        exchange.responseHeaders.add("Content-Type", response.contentType)
        val bytes = response.body.toByteArray()
        exchange.sendResponseHeaders(response.code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class Response(val code: Int, val body: String, val contentType: String)

    /** The claim/submit endpoints drive this process's key, so they must be
     *  unreachable from other web origins: the custom header forces a CORS
     *  preflight no cross-origin page can pass (we never answer preflights),
     *  and the Host check stops DNS-rebinding at the front door. */
    private fun checkSameOrigin(exchange: HttpExchange) {
        val host = exchange.requestHeaders.getFirst("Host").orEmpty().substringBefore(':')
        require(host == "127.0.0.1" || host == "localhost") { "bad Host header" }
        require(exchange.requestHeaders.getFirst("X-Labeler") == "1") {
            "missing X-Labeler header — use the labeler page"
        }
    }

    private fun page(): Response = Response(
        200,
        checkNotNull(javaClass.getResourceAsStream("/labeler.html")).readBytes().decodeToString(),
        "text/html; charset=utf-8",
    )

    /** One aggregate: open jobs (with MY assignment status) + my earnings. */
    private fun state(): Response {
        worker.openJobs().forEach { jobs[it.offer.escrowId] = it }
        return json(
            JsonObject(
                mapOf(
                    "pubkey" to JsonPrimitive(worker.pubkey),
                    "network" to JsonPrimitive(network),
                    "jobs" to JsonArray(jobs.values.sortedBy { it.offer.escrowId }.map(::jobJson)),
                    "earnings" to JsonArray(
                        worker.earnings().map {
                            JsonObject(
                                mapOf(
                                    "escrowId" to JsonPrimitive(it.escrowId),
                                    "txid" to JsonPrimitive(it.txid),
                                    "sats" to JsonPrimitive(it.sats),
                                ),
                            )
                        },
                    ),
                ),
            ),
        )
    }

    private fun jobJson(job: WorkerSession.JobRow): JsonObject {
        val mine = worker.assignments(job).firstOrNull()
        return JsonObject(
            mapOf(
                "escrowId" to JsonPrimitive(job.offer.escrowId),
                "jobType" to JsonPrimitive(job.offer.jobType),
                "rewardPerTaskSats" to JsonPrimitive(job.offer.rewardPerTaskSats),
                "status" to JsonPrimitive(statusName(mine?.status)),
                "tasks" to JsonArray(
                    job.offer.tasks.map {
                        JsonObject(
                            mapOf(
                                "key" to JsonPrimitive(it.key),
                                "question" to JsonPrimitive(it.question),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun statusName(status: AssignmentStatus?): String = when (status) {
        null -> "open"
        AssignmentStatus.CLAIMED -> "claimed"
        AssignmentStatus.ACTIVE -> "active"
        else -> status.name.lowercase()
    }

    private fun claim(body: JsonObject): Response {
        val job = jobFor(body)
        val address = body.getValue("address").jsonPrimitive.content
        require(address.isNotBlank()) { "enter a payout address first" }
        worker.claim(job, address, emptyList())
        return json(JsonObject(mapOf("ok" to JsonPrimitive(true))))
    }

    private fun submit(body: JsonObject): Response {
        val job = jobFor(body)
        val active = worker.assignments(job).firstOrNull { it.status == AssignmentStatus.ACTIVE }
            ?: error("no active assignment for this job")
        val answers = body.getValue("answers").jsonObject.map { (key, value) ->
            Answer(key, value.jsonPrimitive.content)
        }
        worker.submit(job, active, answers)
        return json(JsonObject(mapOf("ok" to JsonPrimitive(true))))
    }

    private fun jobFor(body: JsonObject): WorkerSession.JobRow {
        val escrowId = body.getValue("escrowId").jsonPrimitive.content
        return jobs[escrowId] ?: error("unknown job $escrowId — refresh first")
    }

    private fun json(payload: JsonObject): Response =
        Response(200, payload.toString(), "application/json")
}

fun main() {
    val relays = (System.getenv("HPB_RELAYS") ?: error("HPB_RELAYS is required")).split(",")
    val port = System.getenv("HPB_LABELER_PORT")?.toInt() ?: 7677
    val app = LabelerApp(
        WorkerSession(OkRelayClient(relays), persistentKey()),
        port,
        network = System.getenv("HPB_NETWORK") ?: "SIGNET",
    )
    app.start()
    println("labeler at ${app.url}")
    Thread.currentThread().join()
}

private fun persistentKey(): ByteArray {
    val dir = Path.of(System.getenv("HPB_LABELER_DIR") ?: "${System.getProperty("user.home")}/.hpb-labeler")
    Files.createDirectories(dir)
    val file = dir.resolve("worker.key")
    if (Files.exists(file)) return Files.readString(file).trim().hexBytes()
    val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    Files.writeString(file, key.hex())
    return key
}
