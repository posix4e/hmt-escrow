package org.hpb.harness

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import org.hpb.engine.Network
import org.hpb.headless.DemoConfig
import org.hpb.headless.DemoRun
import org.hpb.headless.DemoTiming
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The exact program an operator runs on signet (`gradle :headless:demo`),
 * smoke-tested on regtest: same runner, same confirmation waits — a
 * background miner stands in for signet's block production. Network
 * differences beyond chain params are zero by construction.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoRunTest {
    private val node = RegtestNode.start()
    private val relay = RelayFixture.start()
    private val mining = AtomicBoolean(true)
    private val miner = Thread {
        while (mining.get()) {
            runCatching { node.mine(1) }
            Thread.sleep(300)
        }
    }.also { it.isDaemon = true; it.start() }

    @AfterAll
    fun tearDown() {
        mining.set(false)
        miner.join(5000)
        node.close()
        relay.close()
    }

    @Test
    fun demoRoundTripsOnRegtest() {
        val cfg = DemoConfig(
            network = Network.REGTEST,
            rpc = node.rpc,
            walletName = "miner",
            relays = listOf(relay.url),
            dir = Files.createTempDirectory("hpb-demo"),
            rewardSats = 1_000,
            timing = DemoTiming(pollMillis = 250, maxWaitMillis = 120_000),
        )
        val receipt = DemoRun(cfg) { }.execute()

        // two workers x two groundtruth tasks, all correct
        assertEquals(2, receipt.lines.size)
        assertEquals(4_000L, receipt.lines.sumOf { it.sats })
        receipt.lines.forEach { line ->
            assertEquals(line.sats, node.addressBalance(line.address))
        }
    }
}
