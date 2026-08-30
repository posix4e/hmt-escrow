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

/**
 * The slice of CVAT's REST API the bridge needs (v2, token auth): read a
 * task's name, label schema, frame count and frame images; append tag
 * annotations. Works against any CVAT >= 2.x — and against [MockCvat] when
 * you want to try the bridge without a CVAT deployment.
 */
class CvatClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newHttpClient()

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

    fun frame(taskId: Long, number: Int): ByteArray =
        raw("/api/tasks/$taskId/data?org=&quality=compressed&type=frame&number=$number")

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

    private fun raw(path: String): ByteArray = request("GET", path, null)

    private fun request(method: String, path: String, body: String?): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Token $token")
            .header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) {
            "CVAT $method $path -> HTTP ${response.statusCode()}: ${response.body().decodeToString().take(200)}"
        }
        return response.body()
    }
}
