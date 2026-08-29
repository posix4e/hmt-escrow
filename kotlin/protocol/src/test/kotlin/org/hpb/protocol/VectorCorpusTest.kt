package org.hpb.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hpb.engine.Secp
import org.hpb.engine.hex
import org.hpb.engine.nostr.Events

/**
 * The cross-language vector corpus: deterministic fixtures (fixed keys and
 * timestamps) covering event codecs, the reducer, validators, and payout
 * computation. Future twins (Swift/iOS) run the SAME file — divergence fails
 * both suites. Generated once; this test verifies byte-exact stability.
 */
class VectorCorpusTest {
    private val path: Path = Path.of("../../docs/vectors/protocol.vectors.json")

    private val launcherKey = ByteArray(32).also { it[31] = 0x11 }
    private val worker1Key = ByteArray(32).also { it[31] = 0x12 }
    private val worker2Key = ByteArray(32).also { it[31] = 0x13 }
    private val attesterKey = ByteArray(32).also { it[31] = 0x14 }
    private val escrowId = "ab".repeat(32)

    private fun offer(): JobOffer = JobOffer(
            escrowId = escrowId,
            escrowAddress = "bcrt1pexample",
            jobType = "text_answer",
            rewardPerTaskSats = 25_000,
            tasks = listOf(Task("t1", "capital of France?"), Task("t2", "2+2?")),
            validation = ValidationPolicy(
                type = ValidationType.GROUNDTRUTH,
                groundtruthHashes = setOf(
                    Validators.groundtruthHash("t1", "Paris"),
                    Validators.groundtruthHash("t2", "4"),
                ),
            ),
            kyc = KycPolicy(required = true, attesters = listOf(Secp.xonlyHex(attesterKey))),
            expiresAt = 2_000_000_000,
        )

    private data class Fixtures(
        val offer: JobOffer,
        val events: List<org.hpb.engine.nostr.NostrEvent>,
        val results: EscrowResults,
        val payouts: List<PayoutLine>,
        val reduced: List<AssignmentState>,
    )

    private fun fixtures(): Fixtures {
        val offer = offer()
        val offerEvent = Offers.toEvent(launcherKey, offer, createdAt = 1_700_000_000)
        val attestation = Attestations.toEvent(
            attesterKey,
            Attestation(
                "org.humanprotocol.kyc.mock.v1", Secp.xonlyHex(worker1Key),
                "valid", 1_700_000_000, 2_000_000_000,
            ),
            createdAt = 1_700_000_001,
        )
        val claim1 = Assignments.claim(
            worker1Key, offerEvent.pubkey,
            Claim(offerEvent.id, escrowId, "bcrt1qworker1", listOf(attestation.id)),
            1_700_000_002,
        )
        val claim2 = Assignments.claim(
            worker2Key, offerEvent.pubkey,
            Claim(offerEvent.id, escrowId, "bcrt1qworker2", emptyList()),
            1_700_000_002,
        )
        val grant1 = Assignments.grant(
            launcherKey, claim1.pubkey,
            Grant(claim1.id, escrowId, true, listOf("t1", "t2"), 1_800_000_000),
            1_700_000_003,
        )
        val grant2 = Assignments.grant(
            launcherKey, claim2.pubkey,
            Grant(claim2.id, escrowId, true, listOf("t1"), 1_800_000_000),
            1_700_000_003,
        )
        val results = EscrowResults(
            escrowId,
            Validators.validate(
                offer.validation,
                listOf(
                    Validators.Submitted("t1", claim1.pubkey, "paris"),
                    Validators.Submitted("t2", claim1.pubkey, "5"),
                    Validators.Submitted("t1", claim2.pubkey, "PARIS "),
                ),
            ),
        )
        val payouts = Validators.payouts(offer.rewardPerTaskSats, results.rows) { worker ->
            if (worker == claim1.pubkey) "bcrt1qworker1" else "bcrt1qworker2"
        }
        val reduced = Reducer.reduce(
            offerEvent, listOf(claim1, claim2, grant1, grant2), now = 1_700_000_010,
        )
        return Fixtures(
            offer,
            listOf(offerEvent, attestation, claim1, claim2, grant1, grant2),
            results, payouts, reduced,
        )
    }

    /**
     * Reducer phase-ordering conformance: a full claim→grant→submit→reveal
     * chain with every referencing event timestamped BEFORE its antecedent.
     * A twin sorting by bare (created_at, id) drops the grant and the reveal
     * and fails this vector; causal-phase ordering yields VALIDATED.
     */
    private fun skewedChain(offerEvent: org.hpb.engine.nostr.NostrEvent): List<org.hpb.engine.nostr.NostrEvent> {
        val worker3Key = ByteArray(32).also { it[31] = 0x15 }
        val claim3 = Assignments.claim(
            worker3Key, offerEvent.pubkey,
            Claim(offerEvent.id, escrowId, "bcrt1qworker3", emptyList()), 1_700_000_009,
        )
        val grant3 = Assignments.grant(
            launcherKey, claim3.pubkey,
            Grant(claim3.id, escrowId, true, listOf("t1"), 1_800_000_000), 1_700_000_008,
        )
        val submission3 = Assignments.submission(
            worker3Key, offerEvent.pubkey,
            Submission(grant3.id, escrowId, listOf(Answer("t1", "Paris"))), 1_700_000_007,
            nonce = ByteArray(32) { 0x24 },
        )
        val reveal3 = Validations.toEvent(
            launcherKey,
            EscrowResults(escrowId, listOf(ResultRow("t1", claim3.pubkey, "Paris", true))),
            1_700_000_006,
        )
        return listOf(claim3, grant3, submission3, reveal3)
    }

    private fun corpus(): JsonObject {
        val f = fixtures()
        val attestation = f.events[1]
        val worker1 = f.events[2].pubkey
        val offerEvent = f.events[0]
        val skewed = skewedChain(offerEvent)
        return JsonObject(
            mapOf(
                "v" to JsonPrimitive(ProtocolKinds.VERSION),
                "manifest_hash" to JsonPrimitive(f.offer.manifestHash().hex()),
                "events" to JsonArray(f.events.map(Events::toJson)),
                "results_json" to JsonPrimitive(f.results.resultsJson()),
                "results_hash" to JsonPrimitive(f.results.resultsHash().hex()),
                "payouts" to JsonArray(f.payouts.map(::payoutJson)),
                "assignment_statuses" to JsonArray(
                    f.reduced.map { JsonPrimitive("${it.worker}:${it.status}") },
                ),
                "skewed_events" to JsonArray(skewed.map(Events::toJson)),
                "skewed_statuses" to JsonArray(
                    Reducer.reduce(offerEvent, skewed, now = 1_700_000_010)
                        .map { JsonPrimitive("${it.worker}:${it.status}") },
                ),
                "attestation_satisfies" to JsonPrimitive(
                    Attestations.satisfies(
                        attestation, KycPolicy(true, listOf(attestation.pubkey)),
                        worker1, now = 1_700_000_010,
                    ),
                ),
            ),
        )
    }

    private fun payoutJson(line: PayoutLine): JsonObject = JsonObject(
        mapOf(
            "worker" to JsonPrimitive(line.worker),
            "address" to JsonPrimitive(line.address),
            "sats" to JsonPrimitive(line.sats),
        ),
    )

    @Test
    fun corpusIsStableAndSelfVerifying() {
        val generated = corpus().toString()
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.writeString(path, generated)
        }
        assertEquals(Files.readString(path), generated, "vector corpus drifted")

        // every event in the corpus must verify independently
        val stored = Pj.parse(Files.readString(path))
        val events = stored.getValue("events") as JsonArray
        assertTrue(events.size >= 6)
        events.forEach {
            assertTrue(Events.verify(Events.fromJson(it as JsonObject)), "corpus event invalid")
        }

        // the timestamp-skewed chain must still fully validate (phase ordering)
        val skewed = (stored.getValue("skewed_statuses") as JsonArray).single()
        assertTrue((skewed as JsonPrimitive).content.endsWith(":VALIDATED"))
    }
}
