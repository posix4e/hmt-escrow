package org.hpb.engine

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RpcException(val code: Int, override val message: String) :
    RuntimeException("bitcoind RPC error $code: $message")

/**
 * Thin bitcoind JSON-RPC client: cookie or user/password auth, per-wallet
 * routing. Bitcoin Core v26+ is required (miniscript-in-tr() signing).
 */
class Rpc private constructor(
    private val baseUrl: String,
    private val auth: () -> String,
    private val walletName: String?,
) {
    private val http = HttpClient.newHttpClient()

    companion object {
        const val MIN_CORE_VERSION = 260_000

        fun withCookie(url: String, cookiePath: Path): Rpc =
            Rpc(url.trimEnd('/'), { Files.readString(cookiePath).trim() }, null)

        fun withUserPass(url: String, user: String, password: String): Rpc =
            Rpc(url.trimEnd('/'), { "$user:$password" }, null)
    }

    fun wallet(name: String): Rpc = Rpc(baseUrl, auth, name)

    fun call(method: String, vararg params: JsonElement): JsonElement {
        val url = if (walletName != null) "$baseUrl/wallet/$walletName" else baseUrl
        val payload = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive("hpb"),
                "method" to JsonPrimitive(method),
                "params" to JsonArray(params.toList()),
            ),
        )
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth().toByteArray()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        val response = Json.parseToJsonElement(body).jsonObject
        val error = response["error"]
        if (error != null && error != JsonNull) {
            val obj = error.jsonObject
            throw RpcException(
                obj.getValue("code").jsonPrimitive.int,
                obj.getValue("message").jsonPrimitive.content,
            )
        }
        return response.getValue("result")
    }

    fun waitReady(timeoutMillis: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            try {
                call("getblockchaininfo")
                return
            } catch (_: Exception) {
                check(System.currentTimeMillis() <= deadline) {
                    "bitcoind did not become ready in time"
                }
                Thread.sleep(250)
            }
        }
    }

    fun assertVersion(): Int {
        val version = call("getnetworkinfo").jsonObject.getValue("version").jsonPrimitive.int
        require(version >= MIN_CORE_VERSION) { "Bitcoin Core >= 26.0 required, got $version" }
        return version
    }

    /** Canonical '<desc>#<checksum>' for the exact string given (Core is authoritative). */
    fun descriptorWithChecksum(descriptor: String): String {
        val info = call("getdescriptorinfo", JsonPrimitive(descriptor)).jsonObject
        return "$descriptor#${info.getValue("checksum").jsonPrimitive.content}"
    }

    fun deriveAddress(descriptor: String): String {
        val addrs = call("deriveaddresses", JsonPrimitive(descriptorWithChecksum(descriptor)))
        return (addrs as JsonArray)[0].jsonPrimitive.content
    }
}
