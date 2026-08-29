package org.hpb.engine

/**
 * Identities are 64-hex x-only secp256k1 pubkeys — the same key works in
 * Taproot descriptors and as a Nostr identity.
 */
object Keys {
    /** BIP341's canonical "nothing up my sleeve" internal key. Escrows are
     *  deliberately identifiable; discovery via the OP_RETURN tag is by design. */
    const val NUMS_XONLY = "50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0"

    /**
     * Deterministic per-job private key for the escrow's genesis address:
     * scalar' = scalar + H_tag(launcher_xonly || sha256(jobId) || nonce || hrp) mod n.
     * Only the launcher can compute it; others learn the resulting address
     * from the escrow announce record. The network's HRP is mixed in so the
     * same job never derives the same escrow (or escrow id) on two networks —
     * cross-network replay fails closed at the identifier level.
     */
    fun genesisPrivateKey(
        launcherPrivkey: ByteArray,
        jobId: String,
        nonce: Long,
        network: Network,
    ): ByteArray {
        val tweak = taggedHash(
            "HMTB/genesis",
            Secp.xonly(launcherPrivkey) + sha256(jobId.toByteArray()) + nonceBytes(nonce) +
                network.hrp.toByteArray(),
        )
        return Secp.privateKeyTweakAdd(launcherPrivkey, tweak)
    }

    private fun nonceBytes(nonce: Long): ByteArray =
        ByteArray(8) { i -> ((nonce ushr (8 * i)) and 0xff).toByte() }

    /** Stable 32-byte escrow identifier committed in OP_RETURN and Nostr tags. */
    fun escrowId(genesisScriptPubKey: ByteArray): ByteArray =
        taggedHash("HMTB/escrow", genesisScriptPubKey)
}
