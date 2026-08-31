package org.hpb.harness

import java.nio.file.Files
import java.nio.file.Path
import org.hpb.engine.hexBytes
import org.hpb.engine.nostr.NostrClient
import org.hpb.engine.nostr.NostrFilter
import org.hpb.protocol.Envelopes
import org.hpb.protocol.ProtocolKinds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Read the co-signing envelopes for one escrow.
 *
 * `RoleStack.await` only reports its last error once it times out, so a
 * settlement that keeps retrying looks identical to one that is merely slow.
 * The witness's refusal, if there is one, is in an envelope encrypted to the
 * launcher — this opens it with the launcher's key from HPB_DEMO_DIR.
 */
class EnvelopeInspectTest {
    @Test
    fun `show the co-sign conversation`() {
        val escrowId = System.getenv("HPB_INSPECT_ESCROW")
        val dir = System.getenv("HPB_DEMO_DIR")
        assumeTrue(!escrowId.isNullOrBlank() && !dir.isNullOrBlank(), "HPB_INSPECT_ESCROW/HPB_DEMO_DIR unset")
        assumeTrue(RealCvat.relays != null, "HPB_RELAYS unset")

        val privkey = Files.readString(Path.of(dir, "launcher.key")).trim().hexBytes()
        NostrClient(RealCvat.relayList()).use { nostr ->
            // The launcher logs a truncated escrow id, so match on a prefix.
            val events = nostr.fetch(
                NostrFilter(kinds = listOf(ProtocolKinds.ENVELOPE), limit = 200),
            ).filter { event -> event.tags.any { it.size > 1 && it[0] == "x" && it[1].startsWith(escrowId) } }
                .sortedBy { it.createdAt }
            println("envelopes for $escrowId: ${events.size}")
            events.forEach { event ->
                val opened = runCatching { Envelopes.open(event, privkey) }
                opened.onSuccess { (type, body) ->
                    println("  ${event.createdAt} ${event.pubkey.take(10)}… $type")
                    println("    $body")
                }
                opened.onFailure { println("  ${event.createdAt} ${event.pubkey.take(10)}… unreadable: ${it.message}") }
            }
        }
    }
}
