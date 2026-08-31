package org.hpb.cvat

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The one place CVAT is spoken to over HTTP, so the transport is fixed once.
 *
 * HTTP/1.1 is not a preference. The JDK client defaults to HTTP/2 and
 * negotiates an h2c upgrade, through which CVAT's proxy drops the request
 * body — every call that carries one then fails with a "JSON parse error"
 * from an empty body. MockCvat cannot reproduce this: com.sun.net.httpserver
 * is HTTP/1.1-only, so no upgrade is ever attempted.
 */
class CvatHttp(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    fun get(path: String): JsonElement = parse(bytes("GET", path, null))

    fun post(path: String, body: String): JsonElement = parse(bytes("POST", path, body))

    fun patch(path: String, body: String): JsonElement = parse(bytes("PATCH", path, body))

    fun delete(path: String) {
        bytes("DELETE", path, null)
    }

    /** Checked: any non-2xx is an error. */
    fun bytes(method: String, path: String, body: String?): ByteArray {
        val response = exchange(method, path, body)
        check(response.statusCode() in HTTP_OK) {
            "CVAT $method $path -> HTTP ${response.statusCode()}: " +
                response.body().decodeToString().take(SNIPPET)
        }
        return response.body()
    }

    /** Unchecked, for callers that must inspect a failure themselves. */
    fun exchange(method: String, path: String, body: String?): HttpResponse<ByteArray> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Token $token")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun parse(raw: ByteArray): JsonElement = Json.parseToJsonElement(raw.decodeToString())

    private companion object {
        val HTTP_OK = 200..299
        const val SNIPPET = 200
    }
}
