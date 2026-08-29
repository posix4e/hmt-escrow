package org.hpb.protocol

import kotlinx.serialization.json.jsonObject
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent
import org.hpb.protocol.Pj.a
import org.hpb.protocol.Pj.b
import org.hpb.protocol.Pj.l
import org.hpb.protocol.Pj.s

data class PayoutLine(val worker: String, val address: String, val sats: Long)

/**
 * The payout receipt: auditable against the on-chain PAYOUT transaction —
 * the txid's OP_RETURN carries sha256(payout_id) and the outputs must cover
 * the listed (address, sats) pairs. Wallets verify via their own chain view.
 */
data class Receipt(
    val escrowId: String,
    val payoutId: String,
    val txid: String,
    val lines: List<PayoutLine>,
    val final: Boolean,
)

object Receipts {
    fun toEvent(privkey: ByteArray, receipt: Receipt, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.RECEIPT,
            tags = listOf(listOf("x", receipt.escrowId)) +
                receipt.lines.map { listOf("p", it.worker) },
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "payout_id" to Pj.str(receipt.payoutId),
                "txid" to Pj.str(receipt.txid),
                "final" to Pj.bool(receipt.final),
                "lines" to Pj.arr(
                    receipt.lines.map {
                        Pj.obj(
                            "worker" to Pj.str(it.worker),
                            "address" to Pj.str(it.address),
                            "sats" to Pj.num(it.sats),
                        )
                    },
                ),
            ).toString(),
            createdAt = createdAt,
        )

    fun fromEvent(event: NostrEvent): Receipt {
        require(event.kind == ProtocolKinds.RECEIPT) { "not a receipt" }
        val content = Pj.parse(event.content)
        return Receipt(
            escrowId = requireNotNull(event.tagValue("x")),
            payoutId = content.s("payout_id"),
            txid = content.s("txid"),
            final = content.b("final"),
            lines = content.a("lines").map {
                val line = it.jsonObject
                PayoutLine(line.s("worker"), line.s("address"), line.l("sats"))
            },
        )
    }
}
