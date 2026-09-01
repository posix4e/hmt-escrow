import XCTest

@testable import HpbCore

/// The Swift half of the label-identity contract, and the side that was wrong.
///
/// Swift's `String` equality is canonical equivalence, so `café` written NFC and
/// NFD compares equal and collapses to one dictionary key — while Kotlin's
/// UTF-16 code-unit equality sees two. Two witnesses would compute different
/// winners and different payouts from identical data. Tallying by
/// `hex(utf8(normalize(...)))` makes equality mean the same thing on both sides.
///
/// The twin is `kotlin/protocol/src/test/kotlin/org/hpb/protocol/LabelIdentityTest.kt`.
final class LabelIdentityTests: XCTestCase {
    private let nfc = "caf\u{00E9}"
    private let nfd = "cafe\u{0301}"

    /// The bug, stated as a fact about the language rather than about our code.
    func testSwiftStringEqualityWouldHaveMergedThem() {
        XCTAssertEqual(nfc, nfd, "Swift compares these equal; Kotlin does not")
    }

    func testComposedAndDecomposedFormsAreDifferentLabels() {
        XCTAssertNotEqual(
            Validators.labelKey(nfc),
            Validators.labelKey(nfd),
            "distinct byte sequences must stay distinct labels in both languages"
        )
    }

    func testKeyIsAsciiHexOfNormalizedUtf8() {
        XCTAssertEqual("636166c3a9", Validators.labelKey(" CAF\u{00C9} "))
        XCTAssertEqual("63616665cc81", Validators.labelKey(nfd))
    }

    /// Tallying by key must not merge them, which is what this side used to do.
    func testTaskSplitBetweenBothFormsHasNoMajority() {
        let rows = [
            Validators.Submitted(taskKey: "t", worker: "worker-a", answer: nfc),
            Validators.Submitted(taskKey: "t", worker: "worker-b", answer: nfd),
        ]
        let policy = ValidationPolicy(type: .agreement, agreementThreshold: 0.6)
        let results = Validators.validate(policy, rows)
        XCTAssertEqual(
            [false, false],
            results.map(\.accepted),
            "one vote each cannot reach a 0.6 quorum; merging them would pay both"
        )
    }
}
