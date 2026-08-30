package org.hpb.cvat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int

/**
 * A tiny in-memory CVAT lookalike, faithful to the API slice [CvatClient]
 * speaks — so the bridge can be tried (and CI-tested) without a CVAT
 * deployment. One task, "animals", with generated cat/dog frames and a
 * cat/dog label schema; posted annotations are captured in [receivedTags].
 */
class MockCvat(port: Int = 0) : AutoCloseable {
    val receivedTags = CopyOnWriteArrayList<CvatTag>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        route("/api/tasks/$TASK_ID/data/meta") { json("""{"size":${FRAMES.size},"start_frame":0}""") }
        route("/api/tasks/$TASK_ID/data") { exchange ->
            val number = exchange.requestURI.query.substringAfter("number=").substringBefore('&').toInt()
            Payload(200, framePng(number), "image/png")
        }
        route("/api/tasks/$TASK_ID/annotations") { exchange ->
            captureTags(exchange.requestBody.readBytes().decodeToString())
            json("""{"version":0}""")
        }
        route("/api/tasks/$TASK_ID") { json("""{"id":$TASK_ID,"name":"animals"}""") }
        route("/api/labels") {
            json(
                """{"results":[{"id":$CAT_LABEL_ID,"name":"cat"},{"id":$DOG_LABEL_ID,"name":"dog"}]}""",
            )
        }
        server.start()
    }

    private data class Payload(val code: Int, val body: ByteArray, val contentType: String)

    private fun json(body: String) = Payload(200, body.toByteArray(), "application/json")

    private fun route(path: String, handler: (HttpExchange) -> Payload) {
        server.createContext(path) { exchange ->
            val payload = runCatching { handler(exchange) }
                .getOrElse { Payload(500, (it.message ?: "error").toByteArray(), "text/plain") }
            exchange.responseHeaders.add("Content-Type", payload.contentType)
            exchange.sendResponseHeaders(payload.code, payload.body.size.toLong())
            exchange.responseBody.use { it.write(payload.body) }
        }
    }

    private fun captureTags(body: String) {
        Json.parseToJsonElement(body).jsonObject.getValue("tags").jsonArray.forEach {
            receivedTags.add(
                CvatTag(
                    it.jsonObject.getValue("frame").jsonPrimitive.int,
                    it.jsonObject.getValue("label_id").jsonPrimitive.long,
                ),
            )
        }
    }

    override fun close() = server.stop(0)

    companion object {
        const val TASK_ID = 1L
        const val CAT_LABEL_ID = 11L
        const val DOG_LABEL_ID = 12L

        /** frame number -> the animal drawn on it (the "right" label). */
        val FRAMES = listOf("cat", "dog", "cat")

        /** A simple recognizable pictogram: pointy ears = cat, floppy = dog. */
        fun framePng(number: Int): ByteArray {
            val image = BufferedImage(192, 144, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0xF2, 0xEF, 0xE7)
            g.fillRect(0, 0, 192, 144)
            drawAnimal(g, FRAMES[number] == "cat")
            g.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            return out.toByteArray()
        }

        private fun drawAnimal(g: Graphics2D, cat: Boolean) {
            g.color = if (cat) Color(0xE8, 0x8D, 0x2E) else Color(0x8B, 0x5A, 0x2B)
            g.fillOval(56, 42, 80, 76) // head
            if (cat) {
                g.fillPolygon(intArrayOf(62, 78, 58), intArrayOf(58, 42, 30), 3)
                g.fillPolygon(intArrayOf(130, 114, 134), intArrayOf(58, 42, 30), 3)
            } else {
                g.fillOval(42, 48, 26, 48)
                g.fillOval(124, 48, 26, 48)
            }
            g.color = Color(0x22, 0x22, 0x22)
            g.fillOval(78, 68, 10, 10)
            g.fillOval(104, 68, 10, 10)
            g.fillOval(91, 86, 10, 8) // nose
        }
    }
}

fun main() {
    val mock = MockCvat(System.getenv("MOCK_CVAT_PORT")?.toInt() ?: 7688)
    println("mock cvat at ${mock.url} (task ${MockCvat.TASK_ID}: 'animals', labels cat/dog)")
    Thread.currentThread().join()
}
