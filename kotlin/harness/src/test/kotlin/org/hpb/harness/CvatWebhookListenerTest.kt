package org.hpb.harness

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hpb.cvat.CvatWebhookListener
import org.junit.jupiter.api.Test

/**
 * The webhook is untrusted input arriving over the network, so the tests that
 * matter are the ones about refusing it.
 */
class CvatWebhookListenerTest {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @Test
    fun `a signed state change is queued`() {
        listener { server ->
            assertEquals(200, post(server.port, stateChange(), sign(stateChange())))
            val event = server.drain().single()
            assertEquals(42L, event.jobId)
            assertEquals("completed", event.state)
            assertEquals(7L, event.assigneeId)
        }
    }

    /** Without this the queue is whatever a stranger decides to put in it. */
    @Test
    fun `a forged signature is refused and queues nothing`() {
        listener { server ->
            assertEquals(401, post(server.port, stateChange(), "sha256=deadbeef"))
            assertTrue(server.drain().isEmpty(), "an unsigned delivery was queued")
        }
    }

    @Test
    fun `a missing signature is refused`() {
        listener { server ->
            assertEquals(401, post(server.port, stateChange(), null))
            assertTrue(server.drain().isEmpty(), "an unsigned delivery was queued")
        }
    }

    /** CVAT sends updates that change nothing about state; they are noise. */
    @Test
    fun `an update with no state change is ignored`() {
        val body = """{"event":"update:job","job":{"id":42,"state":"annotation"},"before_update":{"assignee":null}}"""
        listener { server ->
            assertEquals(200, post(server.port, body, sign(body)))
            assertTrue(server.drain().isEmpty(), "a non-state update was queued")
        }
    }

    @Test
    fun `deliveries are handed out once`() {
        listener { server ->
            post(server.port, stateChange(), sign(stateChange()))
            assertEquals(1, server.drain().size)
            assertTrue(server.drain().isEmpty(), "the same delivery was handed out twice")
        }
    }

    private fun listener(block: (CvatWebhookListener) -> Unit) =
        CvatWebhookListener(SECRET, log = {}).use(block)

    private fun post(port: Int, body: String, signature: String?): Int {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/cvat-webhook"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        signature?.let { builder.header("X-Signature-256", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun stateChange() = """
        {"event":"update:job","job":{"id":42,"state":"completed","assignee":{"id":7},
        "updated_date":"2026-08-31T00:00:00Z"},"before_update":{"state":"annotation"}}
    """.trimIndent().replace("\n", "")

    private companion object {
        const val SECRET = "a-webhook-secret"
    }
}
