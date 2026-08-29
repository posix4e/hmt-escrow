package org.hpb.engine

/**
 * Escrow, genesis, and staking-bond descriptors + BIP341 taptree math.
 *
 * Bitcoin Core (v26+) is the authority for parsing/signing/finalizing; the
 * tapscripts, leaf hashes, output key, and control blocks are computed here so
 * addresses derive offline and spends classify by tapleaf. The derived address
 * is golden-tested against Core's `deriveaddresses`.
 *
 * Vault tree (fixed shape):
 *   tr(NUMS,{multi_a(2,L,RepK,RecK),
 *            {and_v(v:pk(L),older(CANCEL_DELAY)),
 *             and_v(v:pk(RepK),after(EXPIRY_HEIGHT))}})
 */
object Script {
    private const val OP_CHECKSIG = 0xAC
    private const val OP_CHECKSIGVERIFY = 0xAD
    private const val OP_CHECKSIGADD = 0xBA
    private const val OP_CLTV = 0xB1
    private const val OP_CSV = 0xB2
    private const val OP_NUMEQUAL = 0x9C
    const val LEAF_VERSION = 0xC0

    /** Minimal script push of an integer, as Core's CScript << n. */
    fun scriptNum(n: Int): ByteArray {
        require(n >= 1) { "timelock values must be >= 1" }
        if (n <= 16) return byteArrayOf((0x50 + n).toByte())
        var v = n
        val out = ArrayList<Byte>()
        while (v != 0) {
            out.add((v and 0xff).toByte())
            v = v ushr 8
        }
        if (out.last().toInt() and 0x80 != 0) out.add(0x00)
        return byteArrayOf(out.size.toByte()) + out.toByteArray()
    }

    private fun pushKey(xonly: ByteArray) = byteArrayOf(0x20) + xonly

    /** BIP387 multi_a: <k1> CHECKSIG <k2> CHECKSIGADD ... <t> NUMEQUAL. */
    fun multiA(threshold: Int, keys: List<ByteArray>): ByteArray {
        require(threshold in 1..keys.size) { "bad multi_a threshold" }
        var out = pushKey(keys.first()) + byteArrayOf(OP_CHECKSIG.toByte())
        for (key in keys.drop(1)) out += pushKey(key) + byteArrayOf(OP_CHECKSIGADD.toByte())
        return out + scriptNum(threshold) + byteArrayOf(OP_NUMEQUAL.toByte())
    }

    /** and_v(v:pk(K),older(N)). */
    fun csvLeaf(xonly: ByteArray, delayBlocks: Int): ByteArray =
        pushKey(xonly) + byteArrayOf(OP_CHECKSIGVERIFY.toByte()) +
            scriptNum(delayBlocks) + byteArrayOf(OP_CSV.toByte())

    /** and_v(v:pk(K),after(H)). */
    fun cltvLeaf(xonly: ByteArray, lockHeight: Int): ByteArray =
        pushKey(xonly) + byteArrayOf(OP_CHECKSIGVERIFY.toByte()) +
            scriptNum(lockHeight) + byteArrayOf(OP_CLTV.toByte())

    fun tapleafHash(script: ByteArray): ByteArray {
        require(script.size < 253) { "leaf script too large" }
        return taggedHash("TapLeaf", byteArrayOf(LEAF_VERSION.toByte(), script.size.toByte()) + script)
    }

    fun tapbranchHash(a: ByteArray, b: ByteArray): ByteArray {
        val (lo, hi) = if (compareBytes(a, b) <= 0) a to b else b to a
        return taggedHash("TapBranch", lo + hi)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }
}

enum class VaultLeaf { PAYOUT, REFUND, SWEEP }

data class Vault(
    val descriptor: String,
    val address: String,
    val scriptPubKey: ByteArray,
    val launcher: String,
    val cosigner1: String,
    val cosigner2: String,
    val cancelDelayBlocks: Int,
    val expiryHeight: Int,
    val leafScripts: Map<VaultLeaf, ByteArray>,
    val leafHashes: Map<VaultLeaf, ByteArray>,
    val controlBlocks: Map<VaultLeaf, ByteArray>,
) {
    fun leafOf(leafHash: ByteArray): VaultLeaf? =
        leafHashes.entries.firstOrNull { it.value.contentEquals(leafHash) }?.key

    /**
     * The descriptor with one party's x-only key replaced by its WIF —
     * Core signs script-path leaves only for keys it holds privately.
     */
    fun descriptorWithPrivateKey(xonlyHex: String, wif: String): String {
        require(descriptor.contains(xonlyHex)) { "key not in this vault descriptor" }
        return descriptor.replace(xonlyHex, wif)
    }
}

object Descriptors {
    fun vault(
        launcher: String,
        cosigner1: String,
        cosigner2: String,
        cancelDelayBlocks: Int,
        expiryHeight: Int,
        network: Network,
    ): Vault {
        val keys = listOf(launcher, cosigner1, cosigner2).map { Secp.validXonly(it) }
        require(keys.map { it.hex() }.toSet().size == 3) { "vault keys must be distinct" }

        val scripts = mapOf(
            VaultLeaf.PAYOUT to Script.multiA(2, keys),
            VaultLeaf.REFUND to Script.csvLeaf(keys[0], cancelDelayBlocks),
            VaultLeaf.SWEEP to Script.cltvLeaf(keys[1], expiryHeight),
        )
        val hashes = scripts.mapValues { Script.tapleafHash(it.value) }
        val timelockBranch =
            Script.tapbranchHash(hashes.getValue(VaultLeaf.REFUND), hashes.getValue(VaultLeaf.SWEEP))
        val merkleRoot = Script.tapbranchHash(hashes.getValue(VaultLeaf.PAYOUT), timelockBranch)

        val internal = Keys.NUMS_XONLY.hexBytes()
        val (outputKey, parity) = Secp.taprootOutput(internal, merkleRoot)
        val cbPrefix = byteArrayOf((Script.LEAF_VERSION or parity).toByte()) + internal
        val controlBlocks = mapOf(
            VaultLeaf.PAYOUT to cbPrefix + timelockBranch,
            VaultLeaf.REFUND to
                cbPrefix + hashes.getValue(VaultLeaf.SWEEP) + hashes.getValue(VaultLeaf.PAYOUT),
            VaultLeaf.SWEEP to
                cbPrefix + hashes.getValue(VaultLeaf.REFUND) + hashes.getValue(VaultLeaf.PAYOUT),
        )

        val descriptor = "tr(${Keys.NUMS_XONLY},{multi_a(2,$launcher,$cosigner1,$cosigner2)," +
            "{and_v(v:pk($launcher),older($cancelDelayBlocks))," +
            "and_v(v:pk($cosigner1),after($expiryHeight))}})"

        return Vault(
            descriptor = descriptor,
            address = Bech32.p2trAddress(network.hrp, outputKey),
            scriptPubKey = byteArrayOf(0x51, 0x20) + outputKey,
            launcher = launcher,
            cosigner1 = cosigner1,
            cosigner2 = cosigner2,
            cancelDelayBlocks = cancelDelayBlocks,
            expiryHeight = expiryHeight,
            leafScripts = scripts,
            leafHashes = hashes,
            controlBlocks = controlBlocks,
        )
    }

    data class Genesis(
        val descriptor: String,
        val address: String,
        val scriptPubKey: ByteArray,
        val xonly: String,
    )

    /** Launcher-only pre-setup address: tr(<key>) with empty merkle root. */
    fun genesis(xonlyHex: String, network: Network): Genesis {
        val raw = Secp.validXonly(xonlyHex)
        val (outputKey, _) = Secp.taprootOutput(raw, ByteArray(0))
        return Genesis(
            descriptor = "tr($xonlyHex)",
            address = Bech32.p2trAddress(network.hrp, outputKey),
            scriptPubKey = byteArrayOf(0x51, 0x20) + outputKey,
            xonly = xonlyHex,
        )
    }

    data class Bond(
        val descriptor: String,
        val address: String,
        val scriptPubKey: ByteArray,
        val staker: String,
        val unlockHeight: Int,
        val leafScript: ByteArray,
        val leafHash: ByteArray,
        val controlBlock: ByteArray,
    )

    /** Staking bond: single CLTV leaf, spendable by the staker after unlock. */
    fun bond(stakerXonly: String, unlockHeight: Int, network: Network): Bond {
        val raw = Secp.validXonly(stakerXonly)
        val script = Script.cltvLeaf(raw, unlockHeight)
        val leaf = Script.tapleafHash(script)
        val internal = Keys.NUMS_XONLY.hexBytes()
        val (outputKey, parity) = Secp.taprootOutput(internal, leaf)
        return Bond(
            descriptor = "tr(${Keys.NUMS_XONLY},and_v(v:pk($stakerXonly),after($unlockHeight)))",
            address = Bech32.p2trAddress(network.hrp, outputKey),
            scriptPubKey = byteArrayOf(0x51, 0x20) + outputKey,
            staker = stakerXonly,
            unlockHeight = unlockHeight,
            leafScript = script,
            leafHash = leaf,
            controlBlock = byteArrayOf((Script.LEAF_VERSION or parity).toByte()) + internal,
        )
    }
}
