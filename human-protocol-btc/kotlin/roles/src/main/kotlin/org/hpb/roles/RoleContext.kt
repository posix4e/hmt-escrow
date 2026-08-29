package org.hpb.roles

import org.hpb.engine.Network
import org.hpb.engine.PsbtPipeline
import org.hpb.engine.Rpc
import org.hpb.engine.Secp
import org.hpb.engine.escrow.Escrows
import org.hpb.engine.escrow.PsbtSigner
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.wif

/**
 * One participant's world: its key, its OWN chain view (indexer over its own
 * bitcoind), and its relay client. Roles are library logic over this context —
 * a phone app and a headless runner hold exactly the same thing.
 */
class RoleContext(
    val network: Network,
    val rpc: Rpc,
    val indexer: Indexer,
    val nostr: NostrClient,
    val privkey: ByteArray,
) {
    val pubkey: String = Secp.xonlyHex(privkey)
    val escrows = Escrows(network, rpc, indexer)
    val pipeline = PsbtPipeline(rpc)

    fun wif(): String = wif(privkey, network)

    /** Signs the vault's leaves this party's key participates in. */
    fun vaultSigner(escrowId: String): PsbtSigner = PsbtSigner { psbt ->
        val vault = indexer.vaultOf(escrowId, network)
        pipeline.sign(psbt, vault.descriptorWithPrivateKey(pubkey, wif()))
    }

    fun now(): Long = System.currentTimeMillis() / 1000
}
