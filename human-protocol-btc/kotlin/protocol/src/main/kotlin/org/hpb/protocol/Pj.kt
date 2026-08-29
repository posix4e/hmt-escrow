package org.hpb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Tiny helpers for the protocol's hand-built JSON contents. */
internal object Pj {
    fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject =
        JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

    fun str(v: String) = JsonPrimitive(v)
    fun num(v: Long) = JsonPrimitive(v)
    fun num(v: Int) = JsonPrimitive(v)
    fun num(v: Double) = JsonPrimitive(v)
    fun bool(v: Boolean) = JsonPrimitive(v)
    fun arr(items: List<JsonElement>) = JsonArray(items)

    fun parse(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject

    fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content
    fun JsonObject.sOrNull(key: String): String? = this[key]?.jsonPrimitive?.content
    fun JsonObject.l(key: String): Long = getValue(key).jsonPrimitive.long
    fun JsonObject.i(key: String): Int = getValue(key).jsonPrimitive.int
    fun JsonObject.d(key: String): Double = getValue(key).jsonPrimitive.content.toDouble()
    fun JsonObject.b(key: String): Boolean = getValue(key).jsonPrimitive.boolean
    fun JsonObject.a(key: String): JsonArray = getValue(key).jsonArray
    fun JsonObject.o(key: String): JsonObject = getValue(key).jsonObject
}
