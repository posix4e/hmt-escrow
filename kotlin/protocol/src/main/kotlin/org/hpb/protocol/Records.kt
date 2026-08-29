package org.hpb.protocol

import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent
import org.hpb.protocol.Pj.i
import org.hpb.protocol.Pj.l
import org.hpb.protocol.Pj.s

/**
 * Escrow coordination records (kind 9559): the announce that lets any third
 * party derive the full escrow descriptors independently, and the
 * reservation ledger entries every co-signer replays into its own index.
 */
data class Announce(
    val escrowId: String,
    val genesisXonly: String,
    val cosigner1: String,
    val cosigner2: String,
    val cancelDelayBlocks: Int,
    val expiryHeight: Int,
)

data class Reserve(val escrowId: String, val sats: Long, val seq: Long)

object Records {
    fun announce(privkey: ByteArray, a: Announce, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.RECORD,
            tags = listOf(listOf("x", a.escrowId), listOf("t", "announce")),
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "type" to Pj.str("announce"),
                "genesis_xonly" to Pj.str(a.genesisXonly),
                "cosigner1" to Pj.str(a.cosigner1),
                "cosigner2" to Pj.str(a.cosigner2),
                "cancel_delay_blocks" to Pj.num(a.cancelDelayBlocks),
                "expiry_height" to Pj.num(a.expiryHeight),
            ).toString(),
            createdAt = createdAt,
        )

    fun parseAnnounce(event: NostrEvent): Announce {
        val content = Pj.parse(event.content)
        require(content.s("type") == "announce") { "not an announce" }
        return Announce(
            escrowId = requireNotNull(event.tagValue("x")),
            genesisXonly = content.s("genesis_xonly"),
            cosigner1 = content.s("cosigner1"),
            cosigner2 = content.s("cosigner2"),
            cancelDelayBlocks = content.i("cancel_delay_blocks"),
            expiryHeight = content.i("expiry_height"),
        )
    }

    fun reserve(privkey: ByteArray, r: Reserve, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.RECORD,
            tags = listOf(listOf("x", r.escrowId), listOf("t", "reserve")),
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "type" to Pj.str("reserve"),
                "sats" to Pj.num(r.sats),
                "seq" to Pj.num(r.seq),
            ).toString(),
            createdAt = createdAt,
        )

    fun parseReserve(event: NostrEvent): Reserve {
        val content = Pj.parse(event.content)
        require(content.s("type") == "reserve") { "not a reserve" }
        return Reserve(
            escrowId = requireNotNull(event.tagValue("x")),
            sats = content.l("sats"),
            seq = content.l("seq"),
        )
    }

    fun typeOf(event: NostrEvent): String? =
        runCatching { Pj.parse(event.content).s("type") }.getOrNull()
}
