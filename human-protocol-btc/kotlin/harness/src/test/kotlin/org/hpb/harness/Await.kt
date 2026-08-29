package org.hpb.harness

/**
 * Relays are eventually consistent (they acknowledge before events become
 * queryable), so tests poll at every publish->fetch boundary — exactly what
 * real clients do with subscriptions.
 */
fun <T> await(tries: Int = 40, delayMillis: Long = 250, probe: () -> T?): T {
    repeat(tries) {
        runCatching { probe() }.getOrNull()?.let { return it }
        Thread.sleep(delayMillis)
    }
    error("timed out waiting for relay state")
}
