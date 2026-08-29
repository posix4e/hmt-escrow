package org.hpb.engine

import fr.acinq.secp256k1.Secp256k1

/**
 * Thin wrapper over libsecp256k1 (ACINQ JNI bindings) for the operations the
 * engine needs: x-only key handling, BIP341 taproot output tweaking, and
 * deterministic per-job key derivation. Signing happens in Bitcoin Core.
 */
object Secp {
    private val impl = Secp256k1.get()

    fun publicKey(privkey: ByteArray): ByteArray = impl.pubkeyCreate(privkey)

    fun xonly(privkey: ByteArray): ByteArray = compress(publicKey(privkey)).copyOfRange(1, 33)

    fun xonlyHex(privkey: ByteArray): String = xonly(privkey).hex()

    private fun compress(pubkey: ByteArray): ByteArray = impl.pubKeyCompress(pubkey)

    /** Parse an x-only key as a curve point (even-Y lift); throws if invalid. */
    fun liftX(xonly: ByteArray): ByteArray {
        require(xonly.size == 32) { "x-only key must be 32 bytes" }
        return impl.pubkeyParse(byteArrayOf(0x02) + xonly)
    }

    fun validXonly(hex: String): ByteArray {
        require(hex.length == 64) { "expected 64-hex x-only pubkey" }
        val raw = hex.hexBytes()
        liftX(raw)
        return raw
    }

    /**
     * BIP341 output key: lift_x(internal) + H_TapTweak(internal || merkleRoot) * G.
     * Returns the output x-only key and its parity bit (for control blocks).
     */
    fun taprootOutput(internalXonly: ByteArray, merkleRoot: ByteArray): Pair<ByteArray, Int> {
        val tweak = taggedHash("TapTweak", internalXonly + merkleRoot)
        val tweaked = compress(impl.pubKeyTweakAdd(liftX(internalXonly), tweak))
        val parity = if (tweaked[0].toInt() == 0x03) 1 else 0
        return tweaked.copyOfRange(1, 33) to parity
    }

    /** Scalar addition mod n on a private key (per-job genesis derivation).
     *  The JNI binding mutates its input, so operate on a copy. */
    fun privateKeyTweakAdd(privkey: ByteArray, tweak: ByteArray): ByteArray =
        impl.privKeyTweakAdd(privkey.copyOf(), tweak)

    /** BIP340 Schnorr signature over a 32-byte message (Nostr event ids). */
    fun schnorrSign(message32: ByteArray, privkey: ByteArray): ByteArray =
        impl.signSchnorr(message32, privkey, null)

    fun schnorrVerify(signature: ByteArray, message32: ByteArray, xonly: ByteArray): Boolean =
        impl.verifySchnorr(signature, message32, xonly)

    /** Raw X coordinate of the ECDH shared point (NIP-44 needs it unhashed). */
    fun ecdhX(privkey: ByteArray, xonlyPeer: ByteArray): ByteArray {
        val shared = compress(impl.pubKeyTweakMul(liftX(xonlyPeer), privkey.copyOf()))
        return shared.copyOfRange(1, 33)
    }
}
