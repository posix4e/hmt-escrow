package org.hpb.headless

import java.nio.file.Path
import java.security.SecureRandom
import org.hpb.engine.Network
import org.hpb.engine.Rpc
import org.hpb.engine.nostr.NostrClient
import org.hpb.protocol.Answer
import org.hpb.protocol.JobOffer
import org.hpb.protocol.KycPolicy
import org.hpb.protocol.Receipt
import org.hpb.protocol.Task
import org.hpb.protocol.ValidationPolicy
import org.hpb.protocol.ValidationType
import org.hpb.protocol.Validators
import org.hpb.roles.LauncherRole
import org.hpb.roles.WorkerActor

data class DemoTiming(val pollMillis: Long, val maxWaitMillis: Long)

/**
 * Everything a runnable flow needs; [fromEnv] fills signet-first defaults so
 * `HPB_RELAYS=... gradle :headless:demo` against a synced `bitcoind -signet
 * -txindex` with a faucet-funded wallet is a complete invocation. The same
 * config drives production (HPB_NETWORK=MAINNET), the CVAT bridge, and the
 * regtest smoke tests.
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
 * co-signer, and two scripted workers all in this process (roles are
 * libraries — that IS the deployment model), the chain and relays real.
 * On signet or mainnet it waits for real confirmations.
 */
class DemoRun(private val cfg: DemoConfig, private val log: (String) -> Unit = ::println) {
    private val stack = RoleStack(cfg, log)

    fun execute(): Receipt {
        stack.preflight()
        stack.ensureStake()
        val (escrowId, offerEvent) = launchAndFund()
        val submitted = workJob(offerEvent)
        return stack.settle(escrowId, offerEvent, submitted)
    }

    private fun launchAndFund(): Pair<String, org.hpb.engine.nostr.NostrEvent> {
        val job = stack.launcher.createEscrow("demo-${System.currentTimeMillis() / 1000}")
        stack.fundGenesis(job, 4 * cfg.rewardSats + FUND_BUFFER_SATS)
        val offerEvent = stack.launcher.setupAndOffer(
            job, offer(job), stack.witnessCtx.pubkey, stack.cosigner2Ctx.pubkey, cosignerFees = 0 to 0,
        )
        stack.awaitVaultConfirmed(job.escrowId)
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
            actor.claim(offerEvent, stack.newWalletAddress())
        }
        stack.await("grants") {
            stack.launcher.grantClaims(offerEvent, maxWorkers = workers.size)
                .takeIf { it.size >= workers.size }
        }
        workers.forEach { actor ->
            stack.await("grant for ${actor.pubkey.take(8)}…") { actor.grantFor(claims.getValue(actor)) }
            actor.submit(claims.getValue(actor), stack.launcherCtx.pubkey, ANSWERS)
        }
        log("${workers.size} workers claimed and submitted")
        return stack.await("submissions") {
            stack.launcher.collectSubmissions(offerEvent)
                .takeIf { it.map(Validators.Submitted::worker).distinct().size >= workers.size }
        }
    }

    private fun worker() = WorkerActor(
        NostrClient(cfg.relays),
        ByteArray(32).also { SecureRandom().nextBytes(it) },
    )

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
