package org.hpb.engine

/** Bech32m (BIP350) encoding for P2TR (witness v1) addresses. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3
    private val GENERATORS =
        intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) chk = chk xor GENERATORS[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val hi = hrp.map { it.code ushr 5 }
        val lo = hrp.map { it.code and 31 }
        return (hi + listOf(0) + lo).toIntArray()
    }

    private fun convertBits(data: ByteArray): IntArray {
        val out = ArrayList<Int>()
        var acc = 0
        var bits = 0
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.add((acc ushr bits) and 31)
            }
        }
        if (bits > 0) out.add((acc shl (5 - bits)) and 31)
        return out.toIntArray()
    }

    /** Segwit v1 (taproot) address for a 32-byte witness program. */
    fun p2trAddress(hrp: String, program: ByteArray): String {
        require(program.size == 32) { "P2TR program must be 32 bytes" }
        val data = intArrayOf(1) + convertBits(program)
        val checksumInput = hrpExpand(hrp) + data + IntArray(6)
        val poly = polymod(checksumInput) xor BECH32M_CONST
        val checksum = IntArray(6) { (poly ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).map { CHARSET[it] }.joinToString("")
    }
}
