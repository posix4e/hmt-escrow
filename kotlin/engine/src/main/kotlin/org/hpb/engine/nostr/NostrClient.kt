package org.hpb.engine.nostr

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One relay connection: blocking publish and fetch over NIP-01. */
class Relay(private val url: String) : AutoCloseable {
    private val pendingOks = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val buffer = StringBuilder()

    private class Subscription {
        val events = CopyOnWriteArrayList<NostrEvent>()
        val eose = CountDownLatch(1)
    }

    private val socket: WebSocket by lazy {
        HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create(url), Listener()).join()
    }

    private inner class Listener : WebSocket.Listener {
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            buffer.append(data)
            if (last) {
                val message = buffer.toString()
                buffer.setLength(0)
                runCatching { dispatch(Json.parseToJsonElement(message).jsonArray) }
            }
            ws.request(1)
            return null
        }
    }

    private fun dispatch(message: JsonArray) {
        when (message[0].jsonPrimitive.content) {
            "OK" -> pendingOks.remove(message[1].jsonPrimitive.content)
                ?.complete(message[2].jsonPrimitive.content == "true")
            "EVENT" -> subscriptions[message[1].jsonPrimitive.content]
                ?.events?.add(Events.fromJson(message[2].jsonObject))
            "EOSE" -> subscriptions[message[1].jsonPrimitive.content]?.eose?.countDown()
            else -> {}
        }
    }

    /** Send an event; true when the relay acknowledges acceptance. */
    fun publish(event: NostrEvent, timeoutMillis: Long = 5000): Boolean {
        val ok = CompletableFuture<Boolean>()
        pendingOks[event.id] = ok
        socket.sendText(
            JsonArray(listOf(JsonPrimitive("EVENT"), Events.toJson(event))).toString(), true,
        ).join()
        return runCatching { ok.get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    /** One-shot query: REQ, collect until EOSE, CLOSE. Invalid-signature events are dropped. */
    fun fetch(filter: NostrFilter, timeoutMillis: Long = 5000): List<NostrEvent> {
        val subId = UUID.randomUUID().toString().take(16)
        val sub = Subscription()
        subscriptions[subId] = sub
        socket.sendText(
            JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(subId), filter.toJson()))
                .toString(),
            true,
        ).join()
        sub.eose.await(timeoutMillis, TimeUnit.MILLISECONDS)
        socket.sendText(
            JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))).toString(), true,
        ).join()
        subscriptions.remove(subId)
        return sub.events.filter(Events::verify)
    }

    override fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join() }
    }
}

/** Multi-relay front: publish everywhere, read the union — no relay is special. */
class NostrClient(relayUrls: List<String>) : AutoCloseable {
    private val relays = relayUrls.map(::Relay)

    init {
        require(relays.isNotEmpty()) { "at least one relay required" }
    }

    /** True when at least one relay accepted the event. */
    fun publish(event: NostrEvent): Boolean =
        relays.map { runCatching { it.publish(event) }.getOrDefault(false) }.any { it }

    /** Union of all relays' results, deduplicated by event id. */
    fun fetch(filter: NostrFilter): List<NostrEvent> =
        relays.flatMap { runCatching { it.fetch(filter) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }

    override fun close() = relays.forEach(Relay::close)
}

/** The query shapes the protocol uses, as a value (NIP-01 filter). */
data class NostrFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val ids: List<String>? = null,
    val dTag: String? = null,
    val xTag: String? = null,
    val pTag: String? = null,
    val eTag: String? = null,
    val limit: Int? = null,
) {
    fun toJson(): JsonObject {
        val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        putLists(fields)
        putTags(fields)
        limit?.let { fields["limit"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun putLists(fields: MutableMap<String, kotlinx.serialization.json.JsonElement>) {
        kinds?.let { fields["kinds"] = JsonArray(it.map(::JsonPrimitive)) }
        authors?.let { fields["authors"] = JsonArray(it.map(::JsonPrimitive)) }
        ids?.let { fields["ids"] = JsonArray(it.map(::JsonPrimitive)) }
    }

    private fun putTags(fields: MutableMap<String, kotlinx.serialization.json.JsonElement>) {
        listOf("#d" to dTag, "#x" to xTag, "#p" to pTag, "#e" to eTag).forEach { (name, value) ->
            value?.let { fields[name] = JsonArray(listOf(JsonPrimitive(it))) }
        }
    }
}
