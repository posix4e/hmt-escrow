package org.hpb.cvat

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class CvatLabel(val id: Long, val name: String)

data class CvatTag(val frame: Int, val labelId: Long)

/** A frame's bytes together with the media type CVAT actually served. */
class CvatFrame(val bytes: ByteArray, val contentType: String)

/**
 * The slice of CVAT's REST API the bridge needs (v2, token auth): read a
 * task's name, label schema, frame count and frame images; append tag
 * annotations. Works against any CVAT >= 2.x — and against [MockCvat] when
 * you want to try the bridge without a CVAT deployment.
 */
class CvatClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')
    // HTTP/1.1 is not a preference. The JDK client defaults to HTTP/2 and
    // negotiates an h2c upgrade, through which CVAT's proxy drops the request
    // body — every call that carries one (appendTags) then fails with a
    // "JSON parse error" from an empty body. MockCvat cannot reproduce this:
    // com.sun.net.httpserver is HTTP/1.1-only, so no upgrade is ever attempted.
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    fun taskName(taskId: Long): String =
        get("/api/tasks/$taskId").jsonObject.getValue("name").jsonPrimitive.content

    fun labels(taskId: Long): List<CvatLabel> =
        get("/api/labels?task_id=$taskId&page_size=100").jsonObject
            .getValue("results").jsonArray.map {
                CvatLabel(
                    it.jsonObject.getValue("id").jsonPrimitive.long,
                    it.jsonObject.getValue("name").jsonPrimitive.content,
                )
            }

    fun frameCount(taskId: Long): Int =
        get("/api/tasks/$taskId/data/meta").jsonObject.getValue("size").jsonPrimitive.int

    /**
     * CVAT serves `quality=compressed` frames as JPEG, not PNG, so the media
     * type is returned alongside the bytes rather than assumed by the caller.
     */
    fun frame(taskId: Long, number: Int): CvatFrame =
        exchange("GET", "/api/tasks/$taskId/data?org=&quality=compressed&type=frame&number=$number", null)
            .let { CvatFrame(it.body(), it.headers().firstValue("Content-Type").orElse("image/jpeg")) }

    /** PATCH ?action=create — appends tags without touching existing work. */
    fun appendTags(taskId: Long, tags: List<CvatTag>) {
        val body = JsonObject(
            mapOf(
                "version" to JsonPrimitive(0),
                "tags" to JsonArray(
                    tags.map {
                        JsonObject(
                            mapOf(
                                "frame" to JsonPrimitive(it.frame),
                                "label_id" to JsonPrimitive(it.labelId),
                                "group" to JsonPrimitive(0),
                                "source" to JsonPrimitive("auto"),
                                "attributes" to JsonArray(emptyList()),
                            ),
                        )
                    },
                ),
                "shapes" to JsonArray(emptyList()),
                "tracks" to JsonArray(emptyList()),
            ),
        )
        request("PATCH", "/api/tasks/$taskId/annotations?action=create", body.toString())
    }

    private fun get(path: String) = Json.parseToJsonElement(request("GET", path, null).decodeToString())

    private fun request(method: String, path: String, body: String?): ByteArray =
        exchange(method, path, body).body()

    private fun exchange(method: String, path: String, body: String?): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Token $token")
            .header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) {
            "CVAT $method $path -> HTTP ${response.statusCode()}: ${response.body().decodeToString().take(200)}"
        }
        return response
    }
}
