package org.hpb.engine.index

import org.hpb.engine.Descriptors
import org.hpb.engine.Keys
import org.hpb.engine.Rpc
import org.hpb.engine.OpReturn
import org.hpb.engine.Network
import org.hpb.engine.Vault
import org.hpb.engine.hexBytes
import org.hpb.engine.VaultLeaf
import org.hpb.engine.hex

/**
 * The embedded chain reader: registration + record ingestion + lazy sync +
 * state queries. A library inside the participant's process — never a server.
 */
class Indexer(private val db: IndexDb, rpc: Rpc) {
    private val scanner = Scanner(db, rpc)
    private val reader = StateReader(db)

    fun sync() = scanner.sync()

    fun state(escrowId: String): EscrowState {
        sync()
        return reader.state(escrowId)
    }

    /** Register a new escrow at creation time (genesis only; no chain tx yet). */
    fun registerEscrow(
        genesis: Descriptors.Genesis,
        launcher: String,
        jobId: String,
        createdAt: Long,
    ): String {
        val escrowId = Keys.escrowId(genesis.scriptPubKey).hex()
        db.exec(
            "INSERT OR IGNORE INTO escrows(escrow_id, genesis_address, genesis_script, " +
                "launcher, job_id, created_at) VALUES (?,?,?,?,?,?)",
            escrowId, genesis.address, genesis.scriptPubKey.hex(), launcher, jobId, createdAt,
        )
        return escrowId
    }

    /** Attach the vault (at setup build time, before broadcast). */
    fun registerVault(escrowId: String, vault: Vault) {
        db.exec(
            "UPDATE escrows SET vault_address=?, vault_script=?, vault_descriptor=?, " +
                "cosigner1=?, cosigner2=?, cancel_delay=?, expiry_height=?, " +
                "payout_leaf=?, refund_leaf=?, sweep_leaf=? WHERE escrow_id=?",
            vault.address, vault.scriptPubKey.hex(), vault.descriptor,
            vault.cosigner1, vault.cosigner2, vault.cancelDelayBlocks, vault.expiryHeight,
            vault.leafHashes.getValue(VaultLeaf.PAYOUT).hex(),
            vault.leafHashes.getValue(VaultLeaf.REFUND).hex(),
            vault.leafHashes.getValue(VaultLeaf.SWEEP).hex(),
            escrowId,
        )
    }

    fun escrowRow(escrowId: String): Map<String, String?> = db.query(
        "SELECT genesis_address, vault_address, vault_descriptor, launcher, cosigner1, " +
            "cosigner2, setup_txid, manifest_hash FROM escrows WHERE escrow_id=?",
        escrowId,
    ) { rs ->
        mapOf(
            "genesis_address" to rs.getString(1),
            "vault_address" to rs.getString(2),
            "vault_descriptor" to rs.getString(3),
            "launcher" to rs.getString(4),
            "cosigner1" to rs.getString(5),
            "cosigner2" to rs.getString(6),
            "setup_txid" to rs.getString(7),
            "manifest_hash" to rs.getString(8),
        )
    }.firstOrNull() ?: error("unknown escrow $escrowId")

    data class Reservation(
        val eventId: String,
        val escrowId: String,
        val signer: String,
        val amountSats: Long,
        val seq: Long,
        val createdAt: Long,
    )

    /**
     * Ingest a signed reservation record (signature verification happens at
     * the Nostr layer; `verified` reflects it). Acceptance enforces:
     * authorized signer, strictly increasing seq, and amount within remaining.
     *
     * The stored accepted bit is a cache, not a verdict: a record first seen
     * before this index caught up with the chain (vault funds not yet
     * visible) is rejected for lag, not invalidity — a replay re-evaluates
     * and upgrades it once acceptance holds.
     */
    fun ingestReservation(reservation: Reservation, verified: Boolean): Boolean {
        sync() // acceptance depends on chain state (remaining funds)
        val accepted = verified && reservationAllowed(reservation)
        db.exec(
            "INSERT OR IGNORE INTO records(event_id, escrow_id, type, signer, payload, seq, " +
                "created_at, accepted) VALUES (?,?,?,?,?,?,?,?)",
            reservation.eventId, reservation.escrowId, "reserve", reservation.signer,
            reservation.amountSats.toString(), reservation.seq, reservation.createdAt,
            if (accepted) 1 else 0,
        )
        if (accepted) {
            db.exec("UPDATE records SET accepted=1 WHERE event_id=?", reservation.eventId)
            return true
        }
        return db.queryLong(
            "SELECT COALESCE(MAX(accepted),0) FROM records WHERE event_id=?",
            reservation.eventId,
        ) == 1L
    }

    private fun reservationAllowed(r: Reservation): Boolean {
        val row = escrowRow(r.escrowId)
        val authorized = r.signer == row["launcher"] || r.signer == row["cosigner2"]
        val maxSeq = db.queryLong(
            "SELECT COALESCE(MAX(seq),-1) FROM records WHERE escrow_id=? AND type='reserve' " +
                "AND accepted=1",
            r.escrowId,
        )
        return authorized && r.seq > maxSeq && r.amountSats in 1..reader.ledger(r.escrowId).remaining
    }

    fun ingestCancelRequest(
        eventId: String,
        escrowId: String,
        signer: String,
        createdAt: Long,
        verified: Boolean,
    ): Boolean {
        val accepted = verified && signer == escrowRow(escrowId)["launcher"]
        db.exec(
            "INSERT OR IGNORE INTO records(event_id, escrow_id, type, signer, payload, " +
                "created_at, accepted) VALUES (?,?,?,?,?,?,?)",
            eventId, escrowId, "cancel_request", signer, "", createdAt, if (accepted) 1 else 0,
        )
        return accepted
    }

    /** Available (staked, not unstake-marked, unspent) bond sats for a staker. */
    fun availableStake(staker: String): Long = db.queryLong(
        "SELECT COALESCE(SUM(sats),0) FROM stakes WHERE staker=? AND unstake_marked=0 " +
            "AND spent_txid IS NULL",
        staker,
    )

    /** Mark all of a staker's bonds as pending withdrawal (they stop counting). */
    fun markUnstake(staker: String) {
        db.exec("UPDATE stakes SET unstake_marked=1 WHERE staker=?", staker)
    }

    data class BondUtxo(val txid: String, val vout: Int, val sats: Long, val unlockHeight: Int)

    fun withdrawableBonds(staker: String): List<BondUtxo> = db.query(
        "SELECT txid, vout, sats, unlock_height FROM stakes WHERE staker=? AND " +
            "unstake_marked=1 AND spent_txid IS NULL",
        staker,
    ) { BondUtxo(it.getString(1), it.getInt(2), it.getLong(3), it.getInt(4)) }

    data class EscrowUtxo(val txid: String, val vout: Int, val sats: Long)

    fun unspentUtxos(escrowId: String, kind: String): List<EscrowUtxo> = db.query(
        "SELECT txid, vout, sats FROM utxos WHERE escrow_id=? AND kind=? AND spent_txid IS NULL",
        escrowId, kind,
    ) { EscrowUtxo(it.getString(1), it.getInt(2), it.getLong(3)) }

    /** Confirmed payout txid for an idempotent payout id, if any. */
    fun payoutTxid(escrowId: String, payoutIdHash: ByteArray): String? {
        val needle = payoutIdHash.hex()
        return db.query(
            "SELECT txid, tag_hex FROM txs WHERE escrow_id=? AND tag_hex IS NOT NULL",
            escrowId,
        ) { it.getString(1) to it.getString(2) }
            .firstOrNull { (_, tagHex) ->
                val tag = OpReturn.decode(tagHex.hexBytes())
                tag is OpReturn.Payout && tag.payoutIdHash.hex() == needle
            }?.first
    }

    /** The SETUP-committed fee snapshot (cosigner1 %, cosigner2 %). */
    fun feeSnapshot(escrowId: String): Pair<Int, Int> = db.query(
        "SELECT fee1, fee2 FROM escrows WHERE escrow_id=?", escrowId,
    ) { it.getInt(1) to it.getInt(2) }.firstOrNull() ?: (0 to 0)

    /** Rebuild the vault descriptors from the stored escrow row. */
    fun vaultOf(escrowId: String, network: Network): Vault {
        val row = db.query(
            "SELECT launcher, cosigner1, cosigner2, cancel_delay, expiry_height " +
                "FROM escrows WHERE escrow_id=? AND vault_descriptor IS NOT NULL",
            escrowId,
        ) { r ->
            Descriptors.vault(
                r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), network,
            )
        }
        return row.firstOrNull() ?: error("escrow $escrowId has no registered vault")
    }
}
