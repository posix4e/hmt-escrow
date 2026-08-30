package org.hpb.headless

import java.nio.file.Files
import java.security.SecureRandom
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.EscrowStatus
import org.hpb.engine.Network
import org.hpb.engine.escrow.Staking
import org.hpb.engine.hex
import org.hpb.engine.hexBytes
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.nostr.NostrEvent
import org.hpb.engine.nostr.NostrFilter
import org.hpb.engine.satsToBtc
import org.hpb.protocol.Assignments
import org.hpb.protocol.ProtocolKinds
import org.hpb.protocol.Receipt
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole
import org.hpb.roles.RoleContext
import org.hpb.roles.WitnessRole

/**
 * The launcher's whole serverless stack — its own context plus an
 * in-process witness co-signer and second key slot — with the chain-waiting
 * helpers every runnable flow needs. [DemoRun] and the CVAT bridge are both
 * thin scripts over this. Identity keys and role indexes persist under
 * [DemoConfig.dir] so the stake bond survives across runs.
 */
class RoleStack(val cfg: DemoConfig, val log: (String) -> Unit) {
    val wallet = cfg.rpc.wallet(cfg.walletName)
    val launcherCtx = context("launcher")
    val witnessCtx = context("witness")
    val cosigner2Ctx = context("cosigner2")
    val launcher = LauncherRole(launcherCtx)
    val witness = WitnessRole(witnessCtx)

    private fun context(name: String): RoleContext {
        Files.createDirectories(cfg.dir) // before the sqlite files inside it
        return RoleContext(
            cfg.network,
            cfg.rpc,
            Indexer(IndexDb(cfg.dir.resolve("$name.sqlite").toString()), cfg.rpc),
            NostrClient(cfg.relays),
            persistentKey(name),
        )
    }

    private fun persistentKey(name: String): ByteArray {
        val file = cfg.dir.resolve("$name.key")
        if (Files.exists(file)) return Files.readString(file).trim().hexBytes()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        Files.writeString(file, key.hex())
        return key
    }

    fun preflight() {
        cfg.rpc.waitReady()
        cfg.rpc.assertVersion()
        log("network=${cfg.network} launcher=${launcherCtx.pubkey.take(16)}… relays=${cfg.relays}")
    }

    /** Stake once; the bond persists (with the launcher key) across runs. */
    fun ensureStake() {
        val staking = Staking(cfg.network, cfg.rpc, launcherCtx.indexer)
        if (staking.available(launcherCtx.pubkey) >= cfg.network.minStakeSats) return
        log("staking ${cfg.network.minStakeSats} sats from wallet '${cfg.walletName}'…")
        val txid = staking.stake(launcherCtx.pubkey, cfg.network.minStakeSats, wallet)
        log("stake bond ${explorer(txid)}")
        waitConfirmed("stake bond", txid)
    }

    /** Send sats from the wallet to the genesis address and wait for depth. */
    fun fundGenesis(job: LauncherRole.LaunchedJob, sats: Long) {
        log("escrow ${job.escrowId.take(16)}…  funding genesis ${job.genesisAddress} with $sats sats")
        val txid = wallet.call(
            "sendtoaddress", JsonPrimitive(job.genesisAddress), JsonPrimitive(satsToBtc(sats)),
        ).jsonPrimitive.content
        waitConfirmed("genesis funding", txid)
    }

    /** PENDING = the setup sweep is confirmed (balance alone can be the
     *  still-unswept genesis deposit, which cannot fund a payout). */
    fun awaitVaultConfirmed(escrowId: String) {
        await("vault confirmation") {
            launcherCtx.escrows.state(escrowId).takeIf { it.status == EscrowStatus.PENDING }
        }
    }

    /** Payout destinations come from the workers' claims (relay-published). */
    fun payoutAddresses(escrowId: String): (String) -> String {
        val byWorker = launcherCtx.nostr.fetch(
            NostrFilter(kinds = listOf(ProtocolKinds.CLAIM), xTag = escrowId),
        ).associate { it.pubkey to Assignments.parseClaim(it).payoutAddress }
        return byWorker::getValue
    }

    /** Reveal, reserve, co-sign (in-process witness), broadcast, confirm. */
    fun settle(
        escrowId: String,
        offerEvent: NostrEvent,
        submitted: List<Validators.Submitted>,
    ): Receipt {
        val results = launcher.revealAndReserve(offerEvent, submitted)
        val pending = launcher.requestCosign(offerEvent, results, payoutAddresses(escrowId))
        val receipt = await("witness co-sign + payout broadcast") {
            witness.serveOnce()
            launcher.finishPayout(escrowId, pending)
        }
        waitConfirmed("payout", receipt.txid)
        val state = launcherCtx.escrows.state(escrowId)
        check(state.status == EscrowStatus.COMPLETE) { "escrow ended ${state.status}, expected COMPLETE" }
        log("escrow COMPLETE — payout ${explorer(receipt.txid)}")
        receipt.lines.forEach { log("  paid ${it.sats} sats -> ${it.address}") }
        return receipt
    }

    fun waitConfirmed(what: String, txid: String) {
        await("$what confirmation (${cfg.network.minConfirmations} blocks)") {
            runCatching {
                cfg.rpc.call("getrawtransaction", JsonPrimitive(txid), JsonPrimitive(true))
                    .jsonObject["confirmations"]?.jsonPrimitive?.int
            }.getOrNull()?.takeIf { it >= cfg.network.minConfirmations }
        }
    }

    /** Poll until the probe yields; a throwing probe retries, and the last
     *  error is reported on timeout so failures are diagnosable. */
    fun <T> await(what: String, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + cfg.timing.maxWaitMillis
        var lastError: Throwable? = null
        while (true) {
            val attempt = runCatching { probe() }
            attempt.getOrNull()?.let { return it }
            lastError = attempt.exceptionOrNull() ?: lastError
            check(System.currentTimeMillis() <= deadline) {
                "timed out waiting for $what" +
                    (lastError?.let { " — last error: ${it.message}" } ?: "")
            }
            Thread.sleep(cfg.timing.pollMillis)
        }
    }

    fun explorer(txid: String): String = when (cfg.network) {
        Network.MAINNET -> "https://mempool.space/tx/$txid"
        Network.SIGNET -> "https://mempool.space/signet/tx/$txid"
        Network.REGTEST -> txid
    }

    fun newWalletAddress(): String = wallet.call("getnewaddress").jsonPrimitive.content
}
