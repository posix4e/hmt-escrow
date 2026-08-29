package org.hpb.engine.index

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The embedded chain reader's storage — a SQLite file INSIDE the process
 * (this is a library, not a server). Tables hold raw observations only;
 * status and ledger figures are derived at query time.
 */
class IndexDb(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { st.executeUpdate(it) }
        }
    }

    fun exec(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { it.bindAll(args).executeUpdate() }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> {
        conn.prepareStatement(sql).use { st ->
            val rs = st.bindAll(args).executeQuery()
            val out = ArrayList<T>()
            while (rs.next()) out.add(map(rs))
            return out
        }
    }

    fun queryLong(sql: String, vararg args: Any?): Long =
        query(sql, *args) { it.getLong(1) }.firstOrNull() ?: 0L

    override fun close() = conn.close()

    private fun PreparedStatement.bindAll(args: Array<out Any?>): PreparedStatement {
        args.forEachIndexed { i, arg -> setObject(i + 1, arg) }
        return this
    }

    private companion object {
        const val SCHEMA = """
            CREATE TABLE IF NOT EXISTS blocks(
                height INTEGER PRIMARY KEY, hash TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS escrows(
                escrow_id TEXT PRIMARY KEY,
                genesis_address TEXT UNIQUE NOT NULL, genesis_script TEXT NOT NULL,
                vault_address TEXT, vault_script TEXT, vault_descriptor TEXT,
                launcher TEXT NOT NULL, cosigner1 TEXT, cosigner2 TEXT,
                fee1 INTEGER NOT NULL DEFAULT 0, fee2 INTEGER NOT NULL DEFAULT 0,
                job_id TEXT, manifest TEXT, manifest_hash TEXT,
                cancel_delay INTEGER, expiry_height INTEGER,
                payout_leaf TEXT, refund_leaf TEXT, sweep_leaf TEXT,
                created_at INTEGER NOT NULL, setup_txid TEXT);
            CREATE TABLE IF NOT EXISTS txs(
                txid TEXT PRIMARY KEY, height INTEGER NOT NULL, block_time INTEGER NOT NULL,
                escrow_id TEXT, tag_hex TEXT, spends_vault INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS utxos(
                txid TEXT NOT NULL, vout INTEGER NOT NULL,
                address TEXT NOT NULL, sats INTEGER NOT NULL, height INTEGER NOT NULL,
                escrow_id TEXT, kind TEXT NOT NULL, is_deposit INTEGER NOT NULL,
                spent_txid TEXT, spent_height INTEGER, spent_leaf TEXT,
                PRIMARY KEY (txid, vout));
            CREATE TABLE IF NOT EXISTS payout_outputs(
                txid TEXT NOT NULL, vout INTEGER NOT NULL, escrow_id TEXT NOT NULL,
                recipient TEXT NOT NULL, sats INTEGER NOT NULL, height INTEGER NOT NULL,
                PRIMARY KEY (txid, vout));
            CREATE TABLE IF NOT EXISTS records(
                event_id TEXT PRIMARY KEY, escrow_id TEXT, type TEXT NOT NULL,
                signer TEXT NOT NULL, payload TEXT NOT NULL, seq INTEGER,
                created_at INTEGER NOT NULL, accepted INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS stakes(
                txid TEXT NOT NULL, vout INTEGER NOT NULL, staker TEXT NOT NULL,
                sats INTEGER NOT NULL, unlock_height INTEGER NOT NULL, height INTEGER NOT NULL,
                unstake_marked INTEGER NOT NULL DEFAULT 0, spent_txid TEXT,
                PRIMARY KEY (txid, vout));
            CREATE INDEX IF NOT EXISTS idx_utxos_escrow ON utxos(escrow_id);
            CREATE INDEX IF NOT EXISTS idx_txs_escrow ON txs(escrow_id);
            CREATE INDEX IF NOT EXISTS idx_payouts_escrow ON payout_outputs(escrow_id)
        """
    }
}
