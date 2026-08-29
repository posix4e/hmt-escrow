package org.hpb.engine.index

import org.hpb.engine.EscrowStatus
import org.hpb.engine.OpReturn
import org.hpb.engine.hexBytes

/**
 * Deterministic escrow state, derived at query time from raw observations.
 * On-chain facts trump off-chain records; rules are evaluated in order.
 */
data class EscrowState(
    val escrowId: String,
    val status: EscrowStatus,
    val balanceSats: Long,
    val totalFundedSats: Long,
    val amountPaidSats: Long,
    val reservedSats: Long,
    val feeReservationSats: Long,
    val remainingSats: Long,
)

class StateReader(private val db: IndexDb) {

    fun state(escrowId: String): EscrowState {
        val ledger = ledger(escrowId)
        return EscrowState(
            escrowId = escrowId,
            status = status(escrowId, ledger),
            balanceSats = ledger.balance,
            totalFundedSats = ledger.totalFunded,
            amountPaidSats = ledger.amountPaid,
            reservedSats = ledger.reserved,
            feeReservationSats = ledger.feeReservation,
            remainingSats = ledger.remaining,
        )
    }

    internal data class Ledger(
        val balance: Long,
        val totalFunded: Long,
        val amountPaid: Long,
        val reserved: Long,
        val feeReservation: Long,
        val remaining: Long,
    )

    internal fun ledger(escrowId: String): Ledger {
        val balance = db.queryLong(
            "SELECT COALESCE(SUM(sats),0) FROM utxos WHERE escrow_id=? AND spent_txid IS NULL",
            escrowId,
        )
        val totalFunded = db.queryLong(
            "SELECT COALESCE(SUM(sats),0) FROM utxos WHERE escrow_id=? AND is_deposit=1",
            escrowId,
        )
        val amountPaid = db.queryLong(
            "SELECT COALESCE(SUM(sats),0) FROM payout_outputs WHERE escrow_id=?",
            escrowId,
        )
        val reservedGross = db.queryLong(
            "SELECT COALESCE(SUM(CAST(payload AS INTEGER)),0) FROM records " +
                "WHERE escrow_id=? AND type='reserve' AND accepted=1",
            escrowId,
        )
        val fees = db.query(
            "SELECT fee1, fee2 FROM escrows WHERE escrow_id=?",
            escrowId,
        ) { it.getInt(1) to it.getInt(2) }.firstOrNull() ?: (0 to 0)
        val reserved = (reservedGross - amountPaid).coerceAtLeast(0)
        val feeReservation = totalFunded * (fees.first + fees.second) / 100
        return Ledger(
            balance = balance,
            totalFunded = totalFunded,
            amountPaid = amountPaid,
            reserved = reserved,
            feeReservation = feeReservation,
            remaining = (balance - reserved - feeReservation).coerceAtLeast(0),
        )
    }

    /** Ordered rule facts; on-chain facts trump off-chain records. */
    private data class Facts(
        val cancelled: Boolean,
        val complete: Boolean,
        val paid: Boolean,
        val toCancel: Boolean,
        val partial: Boolean,
        val pending: Boolean,
    )

    private fun status(escrowId: String, ledger: Ledger): EscrowStatus =
        statusFrom(facts(escrowId, ledger))

    private fun facts(escrowId: String, ledger: Ledger): Facts {
        val allTags = tags(escrowId)
        val payouts = allTags.filterIsInstance<OpReturn.Payout>()
        val finalized = payouts.any { it.finalized }
        val completeTag = allTags.any { it is OpReturn.Complete }
        val isSetup = setupTxid(escrowId) != null
        return Facts(
            cancelled = cancelled(escrowId, allTags),
            complete = finalized || completeTag,
            paid = payouts.isNotEmpty() && ledger.balance == 0L,
            toCancel = isSetup && cancelRequested(escrowId),
            partial = payouts.isNotEmpty(),
            pending = isSetup,
        )
    }

    /** First matching rule wins; the list order IS the protocol's rule order. */
    private fun statusFrom(f: Facts): EscrowStatus = listOf(
        f.cancelled to EscrowStatus.CANCELLED,
        f.complete to EscrowStatus.COMPLETE,
        f.paid to EscrowStatus.PAID,
        f.toCancel to EscrowStatus.TO_CANCEL,
        f.partial to EscrowStatus.PARTIAL,
        f.pending to EscrowStatus.PENDING,
    ).firstOrNull { it.first }?.second ?: EscrowStatus.LAUNCHED

    private fun setupTxid(escrowId: String): String? =
        db.query("SELECT setup_txid FROM escrows WHERE escrow_id=?", escrowId) {
            it.getString(1)
        }.firstOrNull()

    /** Genesis swept by a non-setup tx, a refund/sweep leaf spend, or a CANCEL tag. */
    private fun cancelled(escrowId: String, allTags: List<OpReturn.Record>): Boolean {
        val genesisSweep = db.queryLong(
            "SELECT COUNT(*) FROM utxos WHERE escrow_id=? AND kind='genesis' " +
                "AND spent_txid IS NOT NULL AND spent_txid IS NOT ?",
            escrowId, setupTxid(escrowId),
        ) > 0
        val timelockLeaf = db.queryLong(
            "SELECT COUNT(*) FROM utxos u JOIN escrows e ON u.escrow_id=e.escrow_id " +
                "WHERE u.escrow_id=? AND u.spent_leaf IN (e.refund_leaf, e.sweep_leaf)",
            escrowId,
        ) > 0
        return genesisSweep || timelockLeaf || allTags.any { it is OpReturn.Cancel }
    }

    private fun cancelRequested(escrowId: String): Boolean = db.queryLong(
        "SELECT COUNT(*) FROM records WHERE escrow_id=? AND type='cancel_request' AND accepted=1",
        escrowId,
    ) > 0

    private fun tags(escrowId: String): List<OpReturn.Record> = db.query(
        "SELECT tag_hex FROM txs WHERE escrow_id=? AND tag_hex IS NOT NULL",
        escrowId,
    ) { it.getString(1) }.mapNotNull { OpReturn.decode(it.hexBytes()) }
}
