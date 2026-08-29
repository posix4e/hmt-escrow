package org.hpb.engine

/**
 * HMTB OP_RETURN protocol records: fixed-layout binary, always <= 80 bytes.
 *
 *   magic "HMTB" | version 0x01 | type | fixed payload
 *
 * Attached to the state-transition transactions themselves, they anchor the
 * off-chain artifacts (manifest/results hashes, fee snapshot, payout ids)
 * that make escrow status derivation deterministic.
 */
object OpReturn {
    private val MAGIC = "HMTB".toByteArray()
    private const val VERSION = 0x01

    const val FLAG_FORCE_COMPLETE = 0x01
    const val FLAG_FINALIZED = 0x02

    sealed interface Record {
        val type: Int

        /** Fixed-layout payload after the type byte. */
        fun payload(): ByteArray
    }

    data class Setup(
        val escrowId: ByteArray,
        val manifestHash: ByteArray,
        val cosigner1FeePct: Int,
        val cosigner2FeePct: Int,
    ) : Record {
        override val type = T_SETUP
        override fun payload(): ByteArray {
            require(cosigner1FeePct + cosigner2FeePct <= 100) { "fees exceed 100%" }
            return c32("escrowId", escrowId) + c32("manifestHash", manifestHash) +
                byteArrayOf(pct("fee1", cosigner1FeePct), pct("fee2", cosigner2FeePct))
        }
    }

    data class Payout(
        val payoutIdHash: ByteArray,
        val resultsHash: ByteArray,
        val forceComplete: Boolean,
        val finalized: Boolean,
    ) : Record {
        override val type = T_PAYOUT
        override fun payload(): ByteArray {
            val flags = (if (forceComplete) FLAG_FORCE_COMPLETE else 0) or
                (if (finalized) FLAG_FINALIZED else 0)
            return c32("payoutIdHash", payoutIdHash) + c32("resultsHash", resultsHash) +
                byteArrayOf(flags.toByte())
        }
    }

    data class Cancel(val escrowId: ByteArray) : Record {
        override val type = T_CANCEL
        override fun payload(): ByteArray = c32("escrowId", escrowId)
    }

    data class Complete(val escrowId: ByteArray, val resultsHash: ByteArray) : Record {
        override val type = T_COMPLETE
        override fun payload(): ByteArray =
            c32("escrowId", escrowId) + c32("resultsHash", resultsHash)
    }

    data object Withdraw : Record {
        override val type = T_WITHDRAW
        override fun payload(): ByteArray = ByteArray(0)
    }

    data class Stake(val staker: ByteArray, val unlockHeight: Long) : Record {
        override val type = T_STAKE
        override fun payload(): ByteArray {
            require(unlockHeight in 1 until (1L shl 32)) { "unlockHeight out of range" }
            return c32("staker", staker) +
                ByteArray(4) { i -> ((unlockHeight ushr (8 * i)) and 0xff).toByte() }
        }
    }

    data class Unknown(override val type: Int, val raw: ByteArray) : Record {
        override fun payload(): ByteArray = throw IllegalArgumentException("cannot encode Unknown")
    }

    private const val T_SETUP = 0x01
    private const val T_PAYOUT = 0x02
    private const val T_CANCEL = 0x03
    private const val T_COMPLETE = 0x04
    private const val T_WITHDRAW = 0x05
    private const val T_STAKE = 0x06

    private data class Codec(val size: Int, val decode: (ByteArray) -> Record)

    private val CODECS: Map<Int, Codec> = mapOf(
        T_SETUP to Codec(66) { p ->
            val fee1 = p[64].toInt() and 0xff
            val fee2 = p[65].toInt() and 0xff
            require(fee1 + fee2 <= 100) { "fees exceed 100%" }
            Setup(p.copyOfRange(0, 32), p.copyOfRange(32, 64), fee1, fee2)
        },
        T_PAYOUT to Codec(65) { p ->
            val flags = p[64].toInt()
            Payout(
                p.copyOfRange(0, 32),
                p.copyOfRange(32, 64),
                flags and FLAG_FORCE_COMPLETE != 0,
                flags and FLAG_FINALIZED != 0,
            )
        },
        T_CANCEL to Codec(32) { p -> Cancel(p.copyOfRange(0, 32)) },
        T_COMPLETE to Codec(64) { p -> Complete(p.copyOfRange(0, 32), p.copyOfRange(32, 64)) },
        T_WITHDRAW to Codec(0) { Withdraw },
        T_STAKE to Codec(36) { p ->
            Stake(
                p.copyOfRange(0, 32),
                (0 until 4).sumOf { (p[32 + it].toLong() and 0xff) shl (8 * it) },
            )
        },
    )

    fun payoutIdHash(payoutId: String): ByteArray = sha256(payoutId.toByteArray())

    private fun c32(name: String, v: ByteArray): ByteArray {
        require(v.size == 32) { "$name must be 32 bytes" }
        return v
    }

    private fun pct(name: String, v: Int): Byte {
        require(v in 0..100) { "$name must be 0..100" }
        return v.toByte()
    }

    fun encode(record: Record): ByteArray {
        val out = MAGIC + byteArrayOf(VERSION.toByte(), record.type.toByte()) + record.payload()
        require(out.size <= 80) { "record exceeds 80 bytes" }
        return out
    }

    /** Decode an HMTB record; null if the payload is not ours. Unknown types
     *  AND malformed known types decode to [Unknown]: chain data is
     *  adversarial, so a third party's garbage under our magic must never
     *  throw (it would wedge every scanner at that block forever). */
    fun decode(data: ByteArray): Record? {
        if (data.size < 6 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        if (data[4].toInt() != VERSION) return null
        val type = data[5].toInt() and 0xff
        val payload = data.copyOfRange(6, data.size)
        val codec = CODECS[type] ?: return Unknown(type, payload)
        if (payload.size != codec.size) return Unknown(type, payload)
        return runCatching { codec.decode(payload) }.getOrElse { Unknown(type, payload) }
    }

    /** Decode an OP_RETURN scriptPubKey (hex) into an HMTB record, if it is one. */
    fun decodeScriptPubKey(scriptHex: String): Record? {
        val raw = scriptHex.hexBytes()
        if (raw.size < 2 || raw[0].toInt() and 0xff != 0x6A) return null
        return extractPush(raw)?.let { decode(it) }
    }

    private fun extractPush(raw: ByteArray): ByteArray? {
        val op = raw[1].toInt() and 0xff
        val (start, length) = when {
            op <= 0x4B -> 2 to op
            op == 0x4C && raw.size >= 3 -> 3 to (raw[2].toInt() and 0xff)
            else -> return null
        }
        return if (raw.size >= start + length) raw.copyOfRange(start, start + length) else null
    }
}
