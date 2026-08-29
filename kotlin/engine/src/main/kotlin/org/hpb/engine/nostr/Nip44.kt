package org.hpb.engine.nostr

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.hpb.engine.Secp

/**
 * NIP-44 v2 encryption (the protocol's channel for submissions and PSBT
 * envelopes), validated against the official vendored test vectors.
 */
object Nip44 {
    private const val VERSION: Byte = 2
    private const val MIN_LEN = 1
    private const val MAX_LEN = 65535

    private fun hmacSha256(key: ByteArray, vararg chunks: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        chunks.forEach(mac::update)
        return mac.doFinal()
    }

    internal fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    internal fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ArrayList<Byte>(length)
        var block = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            block = hmacSha256(prk, block, info, byteArrayOf(counter.toByte()))
            out.addAll(block.toList())
            counter++
        }
        return out.take(length).toByteArray()
    }

    /** Shared conversation key for (our privkey, their x-only pubkey). */
    fun conversationKey(privkey: ByteArray, peerXonly: ByteArray): ByteArray =
        hkdfExtract("nip44-v2".toByteArray(), Secp.ecdhX(privkey, peerXonly))

    internal data class MessageKeys(
        val chachaKey: ByteArray,
        val chachaNonce: ByteArray,
        val hmacKey: ByteArray,
    )

    internal fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        val expanded = hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(
            expanded.copyOfRange(0, 32),
            expanded.copyOfRange(32, 44),
            expanded.copyOfRange(44, 76),
        )
    }

    internal fun paddedLength(unpadded: Int): Int {
        require(unpadded >= MIN_LEN) { "empty plaintext" }
        if (unpadded <= 32) return 32
        val nextPower = Integer.highestOneBit(unpadded - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpadded - 1) / chunk + 1)
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        require(plaintext.size in MIN_LEN..MAX_LEN) { "invalid plaintext length" }
        val padded = ByteArray(2 + paddedLength(plaintext.size))
        padded[0] = (plaintext.size ushr 8).toByte()
        padded[1] = plaintext.size.toByte()
        plaintext.copyInto(padded, 2)
        return padded
    }

    private fun unpad(padded: ByteArray): ByteArray {
        val length = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(length in MIN_LEN..MAX_LEN && padded.size == 2 + paddedLength(length)) {
            "invalid padding"
        }
        return padded.copyOfRange(2, 2 + length)
    }

    private fun chacha(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20")
        cipher.init(
            Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0),
        )
        return cipher.doFinal(data)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray): String {
        val keys = messageKeys(conversationKey, nonce)
        val ciphertext = chacha(keys.chachaKey, keys.chachaNonce, pad(plaintext.toByteArray()))
        val mac = hmacSha256(keys.hmacKey, nonce, ciphertext)
        return Base64.getEncoder()
            .encodeToString(byteArrayOf(VERSION) + nonce + ciphertext + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(!payload.startsWith("#")) { "unsupported future version" }
        val raw = Base64.getDecoder().decode(payload)
        require(raw.size >= 1 + 32 + 32 + 34 && raw[0] == VERSION) { "invalid payload" }
        val nonce = raw.copyOfRange(1, 33)
        val ciphertext = raw.copyOfRange(33, raw.size - 32)
        val mac = raw.copyOfRange(raw.size - 32, raw.size)
        val keys = messageKeys(conversationKey, nonce)
        require(hmacSha256(keys.hmacKey, nonce, ciphertext).contentEquals(mac)) { "invalid MAC" }
        return String(unpad(chacha(keys.chachaKey, keys.chachaNonce, ciphertext)))
    }
}
