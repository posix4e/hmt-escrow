import XCTest
@testable import HpbCore

/// The cross-language vector corpus: this suite REGENERATES the corpus
/// with the same fixed keys and timestamps as the Kotlin reference and
/// compares byte-for-byte against docs/vectors/protocol.vectors.json —
/// any drift between the two implementations fails both suites.
final class VectorCorpusTests: XCTestCase {
    private func key(_ last: UInt8) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 32)
        bytes[31] = last
        return bytes
    }

    private var launcherKey: [UInt8] { key(0x11) }
    private var worker1Key: [UInt8] { key(0x12) }
    private var worker2Key: [UInt8] { key(0x13) }
    private var attesterKey: [UInt8] { key(0x14) }
    private let escrowId = String(repeating: "ab", count: 32)

    private func offer() throws -> JobOffer {
        JobOffer(
            escrowId: escrowId,
            escrowAddress: "bcrt1pexample",
            jobType: "text_answer",
            rewardPerTaskSats: 25_000,
            tasks: [
                TaskItem(key: "t1", question: "capital of France?"),
                TaskItem(key: "t2", question: "2+2?"),
            ],
            validation: ValidationPolicy(
                type: .groundtruth,
                groundtruthHashes: [
                    Validators.groundtruthHash("t1", "Paris"),
                    Validators.groundtruthHash("t2", "4"),
                ]
            ),
            kyc: KycPolicy(required: true, attesters: [try Secp.xonlyHex(attesterKey)]),
            expiresAt: 2_000_000_000
        )
    }

    private struct Fixtures {
        let offer: JobOffer
        let events: [NostrEvent]
        let results: EscrowResults
        let payouts: [PayoutLine]
        let reduced: [AssignmentState]
    }

    private func fixtures() throws -> Fixtures {
        let offer = try offer()
        let offerEvent = try Offers.toEvent(launcherKey, offer, createdAt: 1_700_000_000)
        let attestation = try Attestations.toEvent(
            attesterKey,
            Attestation(
                schema: "org.humanprotocol.kyc.mock.v1",
                subject: try Secp.xonlyHex(worker1Key),
                status: "valid", issuedAt: 1_700_000_000, validUntil: 2_000_000_000
            ),
            createdAt: 1_700_000_001
        )
        let claim1 = try Assignments.claim(
            worker1Key, launcher: offerEvent.pubkey,
            Claim(
                offerEventId: offerEvent.id, escrowId: escrowId,
                payoutAddress: "bcrt1qworker1", attestationEventIds: [attestation.id]
            ),
            createdAt: 1_700_000_002
        )
        let claim2 = try Assignments.claim(
            worker2Key, launcher: offerEvent.pubkey,
            Claim(
                offerEventId: offerEvent.id, escrowId: escrowId,
                payoutAddress: "bcrt1qworker2", attestationEventIds: []
            ),
            createdAt: 1_700_000_002
        )
        let grant1 = try Assignments.grant(
            launcherKey, worker: claim1.pubkey,
            Grant(
                claimEventId: claim1.id, escrowId: escrowId, granted: true,
                taskKeys: ["t1", "t2"], expiresAt: 1_800_000_000
            ),
            createdAt: 1_700_000_003
        )
        let grant2 = try Assignments.grant(
            launcherKey, worker: claim2.pubkey,
            Grant(
                claimEventId: claim2.id, escrowId: escrowId, granted: true,
                taskKeys: ["t1"], expiresAt: 1_800_000_000
            ),
            createdAt: 1_700_000_003
        )
        let results = EscrowResults(
            escrowId: escrowId,
            rows: Validators.validate(
                offer.validation,
                [
                    Validators.Submitted(taskKey: "t1", worker: claim1.pubkey, answer: "paris"),
                    Validators.Submitted(taskKey: "t2", worker: claim1.pubkey, answer: "5"),
                    Validators.Submitted(taskKey: "t1", worker: claim2.pubkey, answer: "PARIS "),
                ]
            )
        )
        let payouts = Validators.payouts(offer.rewardPerTaskSats, results.rows) { worker in
            worker == claim1.pubkey ? "bcrt1qworker1" : "bcrt1qworker2"
        }
        let reduced = Reducer.reduce(
            offerEvent: offerEvent, events: [claim1, claim2, grant1, grant2], now: 1_700_000_010
        )
        return Fixtures(
            offer: offer,
            events: [offerEvent, attestation, claim1, claim2, grant1, grant2],
            results: results, payouts: payouts, reduced: reduced
        )
    }

    /// Reducer phase-ordering conformance: every referencing event is
    /// timestamped BEFORE its antecedent; a twin sorting by bare
    /// (created_at, id) drops the grant and the reveal and fails this
    /// vector — causal-phase ordering yields VALIDATED.
    private func skewedChain(_ offerEvent: NostrEvent) throws -> [NostrEvent] {
        let worker3Key = key(0x15)
        let claim3 = try Assignments.claim(
            worker3Key, launcher: offerEvent.pubkey,
            Claim(
                offerEventId: offerEvent.id, escrowId: escrowId,
                payoutAddress: "bcrt1qworker3", attestationEventIds: []
            ),
            createdAt: 1_700_000_009
        )
        let grant3 = try Assignments.grant(
            launcherKey, worker: claim3.pubkey,
            Grant(
                claimEventId: claim3.id, escrowId: escrowId, granted: true,
                taskKeys: ["t1"], expiresAt: 1_800_000_000
            ),
            createdAt: 1_700_000_008
        )
        let submission3 = try Assignments.submission(
            worker3Key, validatorPubkey: offerEvent.pubkey,
            Submission(
                grantEventId: grant3.id, escrowId: escrowId,
                answers: [Answer(taskKey: "t1", answer: "Paris")]
            ),
            createdAt: 1_700_000_007,
            nonce: [UInt8](repeating: 0x24, count: 32)
        )
        let reveal3 = try Validations.toEvent(
            launcherKey,
            EscrowResults(
                escrowId: escrowId,
                rows: [ResultRow(taskKey: "t1", worker: claim3.pubkey, answer: "Paris", accepted: true)]
            ),
            createdAt: 1_700_000_006
        )
        return [claim3, grant3, submission3, reveal3]
    }

    private func corpus() throws -> String {
        let f = try fixtures()
        let attestation = f.events[1]
        let worker1 = f.events[2].pubkey
        let offerEvent = f.events[0]
        let skewed = try skewedChain(offerEvent)
        return Cj.write(.obj([
            ("v", Cj.num(ProtocolKinds.version)),
            ("manifest_hash", .str(f.offer.manifestHash().hex)),
            ("events", .arr(f.events.map(Events.toJson))),
            ("results_json", .str(f.results.resultsJson())),
            ("results_hash", .str(f.results.resultsHash().hex)),
            ("payouts", .arr(f.payouts.map(payoutJson))),
            ("assignment_statuses", .arr(
                f.reduced.map { .str("\($0.worker):\($0.status.rawValue)") }
            )),
            ("skewed_events", .arr(skewed.map(Events.toJson))),
            ("skewed_statuses", .arr(
                Reducer.reduce(offerEvent: offerEvent, events: skewed, now: 1_700_000_010)
                    .map { .str("\($0.worker):\($0.status.rawValue)") }
            )),
            ("attestation_satisfies", .bool(
                Attestations.satisfies(
                    attestation, policy: KycPolicy(required: true, attesters: [attestation.pubkey]),
                    worker: worker1, now: 1_700_000_010
                )
            )),
        ]))
    }

    private func payoutJson(_ line: PayoutLine) -> J {
        .obj([
            ("worker", .str(line.worker)),
            ("address", .str(line.address)),
            ("sats", Cj.num(line.sats)),
        ])
    }

    func testCorpusIsStableAndSelfVerifying() throws {
        let stored = try String(contentsOf: vectorsUrl("protocol.vectors.json"), encoding: .utf8)
        XCTAssertEqual(stored, try corpus(), "vector corpus drifted between Swift and Kotlin")

        // every event in the corpus must verify independently
        let json = try Cj.parse(stored)
        let events = try json.a("events")
        XCTAssertGreaterThanOrEqual(events.count, 6)
        for eventJson in events {
            XCTAssertTrue(Events.verify(try Events.fromJson(eventJson)), "corpus event invalid")
        }

        // the timestamp-skewed chain must still fully validate (phase ordering)
        let skewed = try json.a("skewed_statuses")
        XCTAssertEqual(1, skewed.count)
        XCTAssertTrue(skewed[0].stringValue?.hasSuffix(":VALIDATED") == true)
    }

    /// The worker decrypt path against a submission the corpus locks in.
    func testSubmissionDecryptsForTheValidator() throws {
        let offerEvent = try fixtures().events[0]
        let submission = try skewedChain(offerEvent)[2]
        let conversationKey = try Nip44.conversationKey(
            launcherKey, submission.pubkey.hexBytes()
        )
        let plaintext = try Nip44.decrypt(submission.content, conversationKey)
        let answers = try Cj.parse(plaintext).a("answers")
        XCTAssertEqual(1, answers.count)
        XCTAssertEqual("t1", try answers[0].s("task_key"))
        XCTAssertEqual("Paris", try answers[0].s("answer"))
    }
}
