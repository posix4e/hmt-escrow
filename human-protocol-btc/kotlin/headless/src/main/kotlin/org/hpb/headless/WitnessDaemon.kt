package org.hpb.headless

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.hpb.engine.Network
import org.hpb.engine.Rpc
import org.hpb.engine.hexBytes
import org.hpb.engine.index.IndexDb
import org.hpb.engine.index.Indexer
import org.hpb.engine.nostr.NostrClient
import org.hpb.roles.RoleContext
import org.hpb.roles.WitnessRole

/**
 * The OPTIONAL headless deployment: exactly the same WitnessRole a phone
 * runs, wrapped in a poll loop for operators who want 24/7 liveness. It is
 * a convenience for responsiveness — never required by the protocol, and it
 * adds no authority the role doesn't already have.
 */
class WitnessDaemon(
    private val witness: WitnessRole,
    private val pollMillis: Long,
) {
    private val running = AtomicBoolean(true)

    fun stop() = running.set(false)

    fun run() {
        while (running.get()) {
            runCatching { witness.serveOnce() }
            Thread.sleep(pollMillis)
        }
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): WitnessDaemon {
            val network = Network.valueOf(env["HPB_NETWORK"] ?: "SIGNET")
            val rpc = Rpc.withCookie(
                env["HPB_RPC_URL"] ?: "http://127.0.0.1:${network.defaultRpcPort}",
                Path.of(env["HPB_RPC_COOKIE"] ?: DemoConfig.defaultCookiePath(network)),
            )
            val privkey = Files.readString(Path.of(env.getValue("HPB_KEY_FILE"))).trim().hexBytes()
            val ctx = RoleContext(
                network,
                rpc,
                Indexer(IndexDb(env.getValue("HPB_DB_PATH")), rpc),
                NostrClient(env.getValue("HPB_RELAYS").split(",")),
                privkey,
            )
            return WitnessDaemon(WitnessRole(ctx), env["HPB_POLL_MS"]?.toLong() ?: 5000L)
        }
    }
}

fun main() {
    WitnessDaemon.fromEnv().run()
}
