package org.hpb.engine

import java.security.MessageDigest

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** BIP340-style tagged hash: sha256(sha256(tag) || sha256(tag) || data). */
fun taggedHash(tag: String, data: ByteArray): ByteArray {
    val tagDigest = sha256(tag.toByteArray())
    return sha256(tagDigest + tagDigest + data)
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.hexBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
