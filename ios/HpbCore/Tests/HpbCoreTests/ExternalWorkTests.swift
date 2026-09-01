import XCTest

@testable import HpbCore

/// The Swift half of the phase-0 encoding. The canonical annotation form is the
/// load-bearing part: this runs on a phone and the recording role re-hashes the
/// same bytes on a server.
final class ExternalWorkTests: XCTestCase {
    private let work = WorkSource(
        tool: "cvat",
        url: "http://cvat.invalid/tasks/12/jobs/42",
        surface: .desktop,
        params: ["org": "hpb", "task_id": "12", "job_id": "42"]
    )

    func testWorkSourceRoundTripsThroughTheQuestion() {
        let question = ExternalWork.question("Annotate job 42", work)
        XCTAssertEqual(work, ExternalWork.workSource(question))
    }

    /// Clients that predate this encoding show `text`; without it they show raw JSON.
    func testQuestionAlwaysCarriesHumanReadableText() throws {
        let question = ExternalWork.question("Annotate job 42", work)
        XCTAssertEqual("Annotate job 42", try Cj.parse(question).s("text"))
    }

    /// A client that has never heard of a tool must still get a usable link.
    func testUnknownToolStillYieldsAWorkSource() {
        let exotic = WorkSource(tool: "label-studio", url: "http://ls.invalid/1")
        let parsed = ExternalWork.workSource(ExternalWork.question("Label it", exotic))
        XCTAssertEqual("label-studio", parsed?.tool)
        XCTAssertEqual("http://ls.invalid/1", parsed?.url)
        XCTAssertEqual(.any, parsed?.surface, "an absent surface should not be desktop")
    }

    func testUnrecognisedSurfaceFallsBackToAny() {
        XCTAssertEqual(.any, WorkSurface.parse("hologram"))
        XCTAssertEqual(.desktop, WorkSurface.parse("DESKTOP"))
    }

    /// Hashing an unknown form would silently withhold a payout later.
    func testUnsupportedResultFormReturnsNil() {
        XCTAssertNil(ExternalWork.canonical("bounding-boxes", []))
    }

    func testInlineTaskIsNotMistakenForExternalWork() {
        XCTAssertNil(ExternalWork.workSource(#"{"text":"pick one","choices":["a","b"]}"#))
        XCTAssertNil(ExternalWork.workSource("just a plain question"))
        XCTAssertNil(ExternalWork.workSource(#"{"work":{"tool":"labelstudio"}}"#), "no url means no work source")
    }

    func testCompletionRoundTripsThroughTheAnswer() {
        let completion = WorkCompletion(ref: "42", resultSha256: "abc123")
        XCTAssertEqual(completion, ExternalWork.completion(ExternalWork.answer(completion)))
    }

    func testPlainAnswerIsNotMistakenForACompletion() {
        XCTAssertNil(ExternalWork.completion("cat"))
        XCTAssertNil(ExternalWork.completion(#"{"completed":"true"}"#))
        XCTAssertNil(ExternalWork.completion(#"{"ref":"42"}"#), "a completion without a hash is not one")
    }

    /// Whatever order CVAT hands them back, both sides must hash the same bytes.
    func testCanonicalAnnotationsIgnoreOrderAndCase() {
        let one = [(2, "Square"), (0, " circle "), (1, "TRIANGLE")]
        let two = [(0, "circle"), (1, "triangle"), (2, "square")]
        XCTAssertEqual("0:circle\n1:triangle\n2:square", ExternalWork.canonicalAnnotations(two))
        XCTAssertEqual(ExternalWork.annotationsHash(two), ExternalWork.annotationsHash(one))
    }

    /// Pinned, and pinned identically in
    /// `kotlin/protocol/src/test/kotlin/org/hpb/protocol/ExternalWorkTest.kt`.
    /// Drift here withholds a payout rather than failing a test.
    func testCanonicalHashIsLockedAcrossLanguages() {
        XCTAssertEqual(
            "627028bd8ef551f3c1fd96097bb70f55ff1b386885198b6294454de79f91d89f",
            ExternalWork.annotationsHash([(0, "circle"), (1, "triangle"), (2, "square")])
        )
    }

    func testDifferentAnnotationsHashDifferently() {
        let hash = ExternalWork.annotationsHash([(0, "circle")])
        XCTAssertNotEqual(hash, ExternalWork.annotationsHash([(0, "square")]))
        XCTAssertNotEqual(hash, ExternalWork.annotationsHash([(1, "circle")]))
    }

    /// A frame may carry more than one tag; the pair, not the frame, is the unit.
    func testMultipleLabelsOnOneFrameAreOrderedDeterministically() {
        XCTAssertEqual(
            "0:circle\n0:square",
            ExternalWork.canonicalAnnotations([(0, "square"), (0, "circle")])
        )
    }
}
