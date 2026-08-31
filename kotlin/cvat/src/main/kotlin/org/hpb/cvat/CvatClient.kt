package org.hpb.cvat

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
class CvatClient(private val http: CvatHttp) {
    constructor(baseUrl: String, token: String) : this(CvatHttp(baseUrl, token))

    fun taskName(taskId: Long): String =
        http.get("/api/tasks/$taskId").jsonObject.getValue("name").jsonPrimitive.content

    fun labels(taskId: Long): List<CvatLabel> =
        http.get("/api/labels?task_id=$taskId&page_size=100").jsonObject
            .getValue("results").jsonArray.map {
                CvatLabel(
                    it.jsonObject.getValue("id").jsonPrimitive.long,
                    it.jsonObject.getValue("name").jsonPrimitive.content,
                )
            }

    fun frameCount(taskId: Long): Int =
        http.get("/api/tasks/$taskId/data/meta").jsonObject.getValue("size").jsonPrimitive.int

    /**
     * CVAT serves `quality=compressed` frames as JPEG, not PNG, so the media
     * type is returned alongside the bytes rather than assumed by the caller.
     */
    fun frame(taskId: Long, number: Int): CvatFrame =
        http.exchange("GET", "/api/tasks/$taskId/data?org=&quality=compressed&type=frame&number=$number", null)
            .let { response ->
                check(response.statusCode() in 200..299) {
                    "CVAT frame $number of task $taskId -> HTTP ${response.statusCode()}"
                }
                CvatFrame(response.body(), response.headers().firstValue("Content-Type").orElse("image/jpeg"))
            }

    /** PATCH ?action=create — appends tags without touching existing work. */
    fun appendTags(taskId: Long, tags: List<CvatTag>) {
        val body = JsonObject(
            mapOf(
                "version" to JsonPrimitive(0),
                "tags" to JsonArray(tags.map(::tagJson)),
                "shapes" to JsonArray(emptyList()),
                "tracks" to JsonArray(emptyList()),
            ),
        )
        http.patch("/api/tasks/$taskId/annotations?action=create", body.toString())
    }

    /** The tags CVAT currently holds for a task, so imports can be read back. */
    fun tags(taskId: Long): List<CvatTag> =
        http.get("/api/tasks/$taskId/annotations").jsonObject
            .getValue("tags").jsonArray.map {
                CvatTag(
                    it.jsonObject.getValue("frame").jsonPrimitive.int,
                    it.jsonObject.getValue("label_id").jsonPrimitive.long,
                )
            }

    private fun tagJson(tag: CvatTag) = JsonObject(
        mapOf(
            "frame" to JsonPrimitive(tag.frame),
            "label_id" to JsonPrimitive(tag.labelId),
            "group" to JsonPrimitive(0),
            "source" to JsonPrimitive("auto"),
            "attributes" to JsonArray(emptyList()),
        ),
    )
}
