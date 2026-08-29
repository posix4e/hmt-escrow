package org.hpb.engine

import java.math.BigInteger

/** Base58Check encoding — needed only for WIF private keys in descriptors. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encodeCheck(payload: ByteArray): String {
        val body = payload + sha256(sha256(payload)).copyOfRange(0, 4)
        val leadingZeros = body.takeWhile { it.toInt() == 0 }.count()
        var n = BigInteger(1, body)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(BigInteger.valueOf(58))
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(leadingZeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }
}

/** WIF for a compressed-pubkey private key on the given network. */
fun wif(privkey: ByteArray, network: Network): String =
    Base58.encodeCheck(byteArrayOf(network.wifPrefix) + privkey + byteArrayOf(0x01))
