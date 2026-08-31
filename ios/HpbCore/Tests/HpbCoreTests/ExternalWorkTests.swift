import XCTest

@testable import HpbCore

/// The Swift half of the phase-0 encoding. The canonical annotation form is the
/// load-bearing part: this runs on a phone and the recording role re-hashes the
/// same bytes on a server.
final class ExternalWorkTests: XCTestCase {
    private let work = CvatWorkSource(
        baseUrl: "http://cvat.invalid",
        org: "hpb",
        taskId: 12,
        jobId: 42,
        labels: ["circle", "square", "triangle"]
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

    func testBrowserUrlPointsAtTheCvatJob() {
        XCTAssertEqual("http://cvat.invalid/tasks/12/jobs/42", work.url)
    }

    func testInlineTaskIsNotMistakenForExternalWork() {
        XCTAssertNil(ExternalWork.workSource(#"{"text":"pick one","choices":["a","b"]}"#))
        XCTAssertNil(ExternalWork.workSource("just a plain question"))
        XCTAssertNil(ExternalWork.workSource(#"{"work":{"tool":"labelstudio","base_url":"x"}}"#))
    }

    func testCompletionRoundTripsThroughTheAnswer() {
        let completion = CvatCompletion(cvatJobId: 42, cvatUserId: 7, annotationsSha256: "abc123")
        XCTAssertEqual(completion, ExternalWork.completion(ExternalWork.answer(completion)))
    }

    func testPlainAnswerIsNotMistakenForACompletion() {
        XCTAssertNil(ExternalWork.completion("cat"))
        XCTAssertNil(ExternalWork.completion(#"{"completed":"true"}"#))
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
