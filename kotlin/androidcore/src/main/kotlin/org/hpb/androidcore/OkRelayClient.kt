package org.hpb.androidcore

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter

/**
 * The mobile relay client: same NIP-01 semantics as the engine's client,
 * built on OkHttp because Android has no java.net.http. Multi-relay: publish
 * everywhere, read the verified, deduplicated union.
 */
class OkRelayClient(private val relayUrls: List<String>) : AutoCloseable {
    private val http = OkHttpClient()
    private val sockets = ConcurrentHashMap<String, RelaySocket>()

    init {
        require(relayUrls.isNotEmpty()) { "at least one relay required" }
    }

    private inner class RelaySocket(url: String) {
        val pendingOks = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
        val subscriptions = ConcurrentHashMap<String, Subscription>()
        val socket: WebSocket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { dispatch(Json.parseToJsonElement(text).jsonArray) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    subscriptions.values.forEach { it.eose.countDown() }
                }
            },
        )

        fun dispatch(message: JsonArray) {
            when (message[0].jsonPrimitive.content) {
                "OK" -> pendingOks.remove(message[1].jsonPrimitive.content)
                    ?.complete(message[2].jsonPrimitive.content == "true")
                "EVENT" -> subscriptions[message[1].jsonPrimitive.content]
                    ?.events?.add(Events.fromJson(message[2].jsonObject))
                "EOSE" -> subscriptions[message[1].jsonPrimitive.content]?.eose?.countDown()
                else -> {}
            }
        }
    }

    private class Subscription {
        val events = CopyOnWriteArrayList<NostrEvent>()
        val eose = CountDownLatch(1)
    }

    private fun socketFor(url: String) = sockets.getOrPut(url) { RelaySocket(url) }

    fun publish(event: NostrEvent, timeoutMillis: Long = 5000): Boolean =
        relayUrls.map { url ->
            runCatching { publishTo(socketFor(url), event, timeoutMillis) }.getOrDefault(false)
        }.any { it }

    private fun publishTo(relay: RelaySocket, event: NostrEvent, timeoutMillis: Long): Boolean {
        val ok = CompletableFuture<Boolean>()
        relay.pendingOks[event.id] = ok
        relay.socket.send(
            JsonArray(listOf(JsonPrimitive("EVENT"), Events.toJson(event))).toString(),
        )
        return runCatching { ok.get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun fetch(filter: NostrFilter, timeoutMillis: Long = 5000): List<NostrEvent> =
        relayUrls.flatMap { url ->
            runCatching { fetchFrom(socketFor(url), filter, timeoutMillis) }
                .getOrDefault(emptyList())
        }.distinctBy { it.id }

    private fun fetchFrom(
        relay: RelaySocket,
        filter: NostrFilter,
        timeoutMillis: Long,
    ): List<NostrEvent> {
        val subId = UUID.randomUUID().toString().take(16)
        val sub = Subscription()
        relay.subscriptions[subId] = sub
        relay.socket.send(
            JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(subId), filter.toJson())).toString(),
        )
        sub.eose.await(timeoutMillis, TimeUnit.MILLISECONDS)
        relay.socket.send(JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))).toString())
        relay.subscriptions.remove(subId)
        return sub.events.filter(Events::verify)
    }

    override fun close() {
        sockets.values.forEach { runCatching { it.socket.close(1000, "bye") } }
        http.dispatcher.executorService.shutdown()
    }
}
