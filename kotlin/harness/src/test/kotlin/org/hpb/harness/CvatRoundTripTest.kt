package org.hpb.harness

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.hpb.androidcore.OkRelayClient
import org.hpb.androidcore.WorkerSession
import org.hpb.cvat.CvatBridge
import org.hpb.cvat.CvatClient
import org.hpb.cvat.CvatTag
import org.hpb.cvat.MockCvat
import org.hpb.engine.Network
import org.hpb.headless.DemoConfig
import org.hpb.headless.DemoTiming
import org.hpb.labeler.LabelerApp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The whole labeling product, end to end on regtest: a (mock) CVAT task is
 * exported to the network by the bridge, a worker labels it through the
 * miniature labeling app's real HTTP API, the payout confirms on-chain, and
 * the consensus labels land back in CVAT as tag annotations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CvatRoundTripTest {
    private val node = RegtestNode.start()
    private val relay = RelayFixture.start()
    private val cvat = MockCvat()
    private val mining = AtomicBoolean(true)
    private val miner = Thread {
        while (mining.get()) {
            runCatching { node.mine(1) }
            Thread.sleep(300)
        }
    }.also { it.isDaemon = true; it.start() }

    private val labeler = LabelerApp(
        WorkerSession(OkRelayClient(listOf(relay.url)), ByteArray(32).also { it[31] = 0x21 }),
        network = "regtest",
    ).also { it.start() }

    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun tearDown() {
        mining.set(false)
        miner.join(5000)
        labeler.stop()
        cvat.close()
        node.close()
        relay.close()
    }

    @Test
    fun cvatTaskIsLabeledThroughTheAppAndPaidOnChain() {
        val outcome = AtomicReference<CvatBridge.Outcome>()
        val bridge = Thread {
            outcome.set(
                CvatBridge(
                    CvatClient(cvat.url, "mock"),
                    DemoConfig(
                        network = Network.REGTEST,
                        rpc = node.rpc,
                        walletName = "miner",
                        relays = listOf(relay.url),
                        dir = Files.createTempDirectory("hpb-cvat"),
                        rewardSats = 1_000,
                        timing = DemoTiming(pollMillis = 250, maxWaitMillis = 180_000),
                    ),
                ) { }.run(MockCvat.TASK_ID, workersWanted = 1, maxFrames = 3),
            )
        }.also { it.isDaemon = true; it.start() }

        // the worker's side happens ONLY through the labeler app's HTTP API
        val job = await(tries = 400) { openJob() }
        val tasks = job.getValue("tasks").jsonArray
        assertEquals(3, tasks.size)
        tasks.forEach {
            val question = it.jsonObject.getValue("question").jsonPrimitive.content
            assertTrue("data:image/png;base64," in question && "choices" in question)
        }
        val escrowId = job.getValue("escrowId").jsonPrimitive.content
        val address = node.newAddress()
        post("claim", """{"escrowId":"$escrowId","address":"$address"}""")
        await(tries = 400) { statusOf(escrowId).takeIf { it == "active" } }
        post(
            "submit",
            """{"escrowId":"$escrowId","answers":{"frame-0":"cat","frame-1":"dog","frame-2":"cat"}}""",
        )

        bridge.join(180_000)
        val result = checkNotNull(outcome.get()) { "bridge did not finish" }

        val expected = listOf(
            CvatTag(0, MockCvat.CAT_LABEL_ID),
            CvatTag(1, MockCvat.DOG_LABEL_ID),
            CvatTag(2, MockCvat.CAT_LABEL_ID),
        )
        assertEquals(expected, result.tags)
        assertEquals(expected, cvat.receivedTags.toList())
        assertEquals(3_000L, node.addressBalance(address))
        await(tries = 400) { statusOf(escrowId).takeIf { it == "validated" } }
        assertEquals(3_000L, earningsTotal())
    }

    private fun state(): JsonObject = Json.parseToJsonElement(
        http.send(
            HttpRequest.newBuilder(URI.create("${labeler.url}api/state")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body(),
    ).jsonObject

    private fun openJob(): JsonObject? = state().getValue("jobs").jsonArray
        .map { it.jsonObject }
        .firstOrNull { it.getValue("status").jsonPrimitive.content == "open" }

    private fun statusOf(escrowId: String): String? = state().getValue("jobs").jsonArray
        .map { it.jsonObject }
        .firstOrNull { it.getValue("escrowId").jsonPrimitive.content == escrowId }
        ?.getValue("status")?.jsonPrimitive?.content

    private fun earningsTotal(): Long = state().getValue("earnings").jsonArray
        .sumOf { it.jsonObject.getValue("sats").jsonPrimitive.long }

    private fun post(path: String, body: String) {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${labeler.url}api/$path"))
                .header("X-Labeler", "1")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 200) { "POST $path -> ${response.statusCode()}: ${response.body()}" }
    }

    @Test
    fun labelerServesPhonesOnAConfiguredLanBind() {
        // a phone can't reach 127.0.0.1 on the workstation: the operator
        // binds the LAN address and the phone opens that URL
        val lan = lanAddress()
        val phoneApp = LabelerApp(
            WorkerSession(OkRelayClient(listOf(relay.url)), ByteArray(32).also { it[31] = 0x22 }),
            network = "regtest",
            bind = lan,
        ).also { it.start() }
        try {
            val page = http.send(
                HttpRequest.newBuilder(URI.create(phoneApp.url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, page.statusCode())
            assertTrue("hpb labeler" in page.body())
            // the phone's requests carry Host: <bind> — the origin gate
            // passes (this claim then fails past the gate, on the fake job)
            assertTrue("unknown job" in rawClaim(lan, phoneApp.port, host = lan))
            // a DNS-rebound page carries its own hostname — still refused
            assertTrue("bad Host header" in rawClaim(lan, phoneApp.port, host = "evil.example"))
        } finally {
            phoneApp.stop()
        }
    }

    /** Raw socket because HttpClient refuses to forge the Host header. */
    private fun rawClaim(address: String, port: Int, host: String): String =
        Socket(address, port).use { socket ->
            val body = """{"escrowId":"x","address":"y"}"""
            socket.getOutputStream().write(
                ("POST /api/claim HTTP/1.1\r\nHost: $host\r\nX-Labeler: 1\r\n" +
                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
            )
            socket.getInputStream().readBytes().decodeToString()
        }

    private fun lanAddress(): String = Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>()
        .first().hostAddress

    @Test
    fun mutatingApiRefusesForeignOrigins() {
        // a cross-site page can send text/plain POSTs but cannot attach the
        // custom header (that would require a CORS preflight we never pass)
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${labeler.url}api/claim"))
                .POST(HttpRequest.BodyPublishers.ofString("""{"escrowId":"x","address":"y"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(500, response.statusCode())
        assertTrue("X-Labeler" in response.body())
    }
}
