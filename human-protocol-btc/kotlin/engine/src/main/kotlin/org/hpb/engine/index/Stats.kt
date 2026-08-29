package org.hpb.engine.index

import org.hpb.engine.EscrowStatus

/**
 * Chain-exact network statistics — the distributed dashboard's data source.
 * Full participants derive these from their own chain view; mobile clients
 * show event-derived aggregates and spot-verify against receipts.
 */
data class NetworkStats(
    val escrowCount: Int,
    val byStatus: Map<EscrowStatus, Int>,
    val totalEscrowedSats: Long,
    val totalPaidSats: Long,
    val payoutRecipients: Int,
    val activeStakeSats: Long,
)

class Stats(private val db: IndexDb) {
    private val reader = StateReader(db)

    fun network(): NetworkStats {
        val ids = db.query("SELECT escrow_id FROM escrows") { it.getString(1) }
        val statuses = ids.map { reader.state(it).status }
        return NetworkStats(
            escrowCount = ids.size,
            byStatus = statuses.groupingBy { it }.eachCount(),
            totalEscrowedSats = db.queryLong(
                "SELECT COALESCE(SUM(sats),0) FROM utxos WHERE is_deposit=1",
            ),
            totalPaidSats = db.queryLong("SELECT COALESCE(SUM(sats),0) FROM payout_outputs"),
            payoutRecipients = db.queryLong(
                "SELECT COUNT(DISTINCT recipient) FROM payout_outputs",
            ).toInt(),
            activeStakeSats = db.queryLong(
                "SELECT COALESCE(SUM(sats),0) FROM stakes WHERE unstake_marked=0 " +
                    "AND spent_txid IS NULL",
            ),
        )
    }
}
