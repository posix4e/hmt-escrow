package org.hpb.headless

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.EscrowStatus
import org.hpb.engine.Network
import org.hpb.engine.Rpc
import org.hpb.engine.escrow.Staking
import org.hpb.engine.hex
import org.hpb.engine.hexBytes
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.satsToBtc
import org.hpb.protocol.Answer
import org.hpb.protocol.JobOffer
import org.hpb.protocol.KycPolicy
import org.hpb.protocol.Receipt
import org.hpb.protocol.Task
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole
import org.hpb.roles.RoleContext
import org.hpb.roles.WitnessRole
import org.hpb.roles.WorkerActor

data class DemoTiming(val pollMillis: Long, val maxWaitMillis: Long)

/**
 * Everything the demo needs; [fromEnv] fills signet-first defaults so
 * `HPB_RELAYS=... gradle :headless:demo` against a synced `bitcoind -signet
 * -txindex` with a faucet-funded wallet is a complete invocation. The same
 * runner drives production (HPB_NETWORK=MAINNET) and the regtest smoke test.
 */
data class DemoConfig(
    val network: Network,
    val rpc: Rpc,
    val walletName: String,
    val relays: List<String>,
    val dir: Path,
    val rewardSats: Long,
    val timing: DemoTiming,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): DemoConfig {
            val network = Network.valueOf(env["HPB_NETWORK"] ?: "SIGNET")
            return DemoConfig(
                network = network,
                rpc = rpcFromEnv(env, network),
                walletName = env["HPB_WALLET"] ?: "hpb",
                relays = (env["HPB_RELAYS"] ?: error("HPB_RELAYS is required")).split(","),
                dir = Path.of(env["HPB_DEMO_DIR"] ?: defaultDemoDir(network)),
                rewardSats = longEnv(env, "HPB_REWARD_SATS", 1_000L),
                timing = DemoTiming(
                    pollMillis = longEnv(env, "HPB_POLL_MS", 5_000L),
                    maxWaitMillis = longEnv(env, "HPB_MAX_WAIT_MS", 40L * 60 * 1000),
                ),
            )
        }

        private fun rpcFromEnv(env: Map<String, String>, network: Network): Rpc = Rpc.withCookie(
            env["HPB_RPC_URL"] ?: "http://127.0.0.1:${network.defaultRpcPort}",
            Path.of(env["HPB_RPC_COOKIE"] ?: defaultCookiePath(network)),
        )

        private fun longEnv(env: Map<String, String>, name: String, default: Long): Long =
            env[name]?.toLong() ?: default

        fun defaultCookiePath(network: Network): String {
            val base = "${System.getProperty("user.home")}/.bitcoin"
            val sub = network.datadirSubdir
            return if (sub.isEmpty()) "$base/.cookie" else "$base/$sub/.cookie"
        }

        private fun defaultDemoDir(network: Network): String =
            "${System.getProperty("user.home")}/.hpb-demo/${network.name.lowercase()}"
    }
}

/**
 * The serverless round-trip as a runnable program: launcher, witness
 * co-signer, and two workers all in this process (roles are libraries —
 * that IS the deployment model), the chain and relays real. On signet or
 * mainnet it waits for real confirmations; identity keys and role indexes
 * persist under [DemoConfig.dir] so the stake bond survives across runs.
 */
class DemoRun(private val cfg: DemoConfig, private val log: (String) -> Unit = ::println) {
    private val wallet = cfg.rpc.wallet(cfg.walletName)
    private val launcherCtx = context("launcher")
    private val witnessCtx = context("witness")
    private val cosigner2Ctx = context("cosigner2")
    private val launcher = LauncherRole(launcherCtx)
    private val witness = WitnessRole(witnessCtx)

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

    fun execute(): Receipt {
        preflight()
        ensureStake()
        val (escrowId, offerEvent) = launchAndFund()
        val submitted = workJob(offerEvent)
        val receipt = payout(escrowId, offerEvent, submitted)
        conclude(escrowId, receipt)
        return receipt
    }

    private fun preflight() {
        cfg.rpc.waitReady()
        cfg.rpc.assertVersion()
        log("network=${cfg.network} launcher=${launcherCtx.pubkey.take(16)}… relays=${cfg.relays}")
    }

    /** Stake once; the bond persists (with the launcher key) across runs. */
    private fun ensureStake() {
        val staking = Staking(cfg.network, cfg.rpc, launcherCtx.indexer)
        if (staking.available(launcherCtx.pubkey) >= cfg.network.minStakeSats) return
        log("staking ${cfg.network.minStakeSats} sats from wallet '${cfg.walletName}'…")
        val txid = staking.stake(launcherCtx.pubkey, cfg.network.minStakeSats, wallet)
        log("stake bond ${explorer(txid)}")
        waitConfirmed("stake bond", txid)
    }

    private fun launchAndFund(): Pair<String, org.hpb.engine.nostr.NostrEvent> {
        val job = launcher.createEscrow("demo-${System.currentTimeMillis() / 1000}")
        val fundSats = 4 * cfg.rewardSats + FUND_BUFFER_SATS
        log("escrow ${job.escrowId.take(16)}…  funding genesis ${job.genesisAddress} with $fundSats sats")
        val fundTxid = wallet.call(
            "sendtoaddress", JsonPrimitive(job.genesisAddress), JsonPrimitive(satsToBtc(fundSats)),
        ).jsonPrimitive.content
        waitConfirmed("genesis funding", fundTxid)
        val offerEvent = launcher.setupAndOffer(
            job, offer(job), witnessCtx.pubkey, cosigner2Ctx.pubkey, cosignerFees = 0 to 0,
        )
        // PENDING = the setup sweep is confirmed (balance alone can be the
        // still-unswept genesis deposit, which cannot fund a payout).
        await("vault confirmation") {
            launcherCtx.escrows.state(job.escrowId).takeIf { it.status == EscrowStatus.PENDING }
        }
        log("vault funded and confirmed; offer ${offerEvent.id.take(16)}… published")
        return job.escrowId to offerEvent
    }

    private fun offer(job: LauncherRole.LaunchedJob) = JobOffer(
        escrowId = job.escrowId,
        escrowAddress = job.genesisAddress,
        jobType = "text_answer",
        rewardPerTaskSats = cfg.rewardSats,
        tasks = listOf(Task("t1", "cat or dog?"), Task("t2", "2+2?")),
        validation = ValidationPolicy(
            ValidationType.GROUNDTRUTH,
            groundtruthHashes = setOf(
                Validators.groundtruthHash("t1", "cat"),
                Validators.groundtruthHash("t2", "4"),
            ),
        ),
        kyc = KycPolicy(required = false),
        expiresAt = System.currentTimeMillis() / 1000 + 86_400,
    )

    private fun workJob(offerEvent: org.hpb.engine.nostr.NostrEvent): List<Validators.Submitted> {
        val workers = listOf(worker(), worker())
        val claims = workers.associateWith { actor ->
            actor.claim(offerEvent, newWalletAddress())
        }
        await("grants") {
            launcher.grantClaims(offerEvent, maxWorkers = workers.size)
                .takeIf { it.size >= workers.size }
        }
        workers.forEach { actor ->
            await("grant for ${actor.pubkey.take(8)}…") { actor.grantFor(claims.getValue(actor)) }
            actor.submit(claims.getValue(actor), launcherCtx.pubkey, ANSWERS)
        }
        log("${workers.size} workers claimed and submitted")
        return await("submissions") {
            launcher.collectSubmissions(offerEvent)
                .takeIf { it.map(Validators.Submitted::worker).distinct().size >= workers.size }
        }
    }

    private fun worker() = WorkerActor(
        NostrClient(cfg.relays),
        ByteArray(32).also { SecureRandom().nextBytes(it) },
    )

    private fun newWalletAddress(): String =
        wallet.call("getnewaddress").jsonPrimitive.content

    private fun payout(
        escrowId: String,
        offerEvent: org.hpb.engine.nostr.NostrEvent,
        submitted: List<Validators.Submitted>,
    ): Receipt {
        val results = launcher.revealAndReserve(offerEvent, submitted)
        val addressOf = payoutAddresses(escrowId)
        val pending = launcher.requestCosign(offerEvent, results, addressOf)
        val receipt = await("witness co-sign + payout broadcast") {
            witness.serveOnce()
            launcher.finishPayout(escrowId, pending)
        }
        waitConfirmed("payout", receipt.txid)
        return receipt
    }

    /** Payout destinations come from the workers' claims (relay-published). */
    private fun payoutAddresses(escrowId: String): (String) -> String {
        val byWorker = launcherCtx.nostr.fetch(
            org.hpb.engine.nostr.NostrFilter(
                kinds = listOf(org.hpb.protocol.ProtocolKinds.CLAIM), xTag = escrowId,
            ),
        ).associate { it.pubkey to org.hpb.protocol.Assignments.parseClaim(it).payoutAddress }
        return byWorker::getValue
    }

    private fun conclude(escrowId: String, receipt: Receipt) {
        val state = launcherCtx.escrows.state(escrowId)
        check(state.status == EscrowStatus.COMPLETE) { "escrow ended ${state.status}, expected COMPLETE" }
        log("escrow COMPLETE — payout ${explorer(receipt.txid)}")
        receipt.lines.forEach { log("  paid ${it.sats} sats -> ${it.address}") }
    }

    private fun waitConfirmed(what: String, txid: String) {
        await("$what confirmation (${cfg.network.minConfirmations} blocks)") {
            runCatching {
                cfg.rpc.call("getrawtransaction", JsonPrimitive(txid), JsonPrimitive(true))
                    .jsonObject["confirmations"]?.jsonPrimitive?.int
            }.getOrNull()?.takeIf { it >= cfg.network.minConfirmations }
        }
    }

    /** Poll until the probe yields; a throwing probe retries, and the last
     *  error is reported on timeout so failures are diagnosable. */
    private fun <T> await(what: String, probe: () -> T?): T {
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

    private fun explorer(txid: String): String = when (cfg.network) {
        Network.MAINNET -> "https://mempool.space/tx/$txid"
        Network.SIGNET -> "https://mempool.space/signet/tx/$txid"
        Network.REGTEST -> txid
    }

    private companion object {
        /** Covers mining fees + the launcher's finalize refund (must clear dust). */
        const val FUND_BUFFER_SATS = 20_000L
        val ANSWERS = listOf(Answer("t1", "cat"), Answer("t2", "4"))
    }
}

fun main() {
    val receipt = DemoRun(DemoConfig.fromEnv()).execute()
    println("demo complete: ${receipt.lines.size} workers paid in ${receipt.txid}")
}
