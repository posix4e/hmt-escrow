import XCTest

@testable import HpbCore

/// The Swift twin of `kotlin/protocol/src/test/kotlin/org/hpb/protocol/CanonicalJsonTest.kt`,
/// pinning the same literals.
///
/// Serialization is consensus: event ids are hashes of serialized events and the
/// on-chain manifest commitment is a hash of a serialized offer. Two
/// implementations that disagree by one byte disagree about money, so these
/// rules are written down in `docs/protocol.md` and asserted identically on both
/// sides rather than left to the fixtures to imply.
final class CanonicalJsonTests: XCTestCase {
    func testNoInsignificantWhitespace() {
        XCTAssertEqual(
            #"{"a":1,"b":[1,2]}"#,
            Cj.write(Cj.obj([("a", Cj.num(1)), ("b", .arr([Cj.num(1), Cj.num(2)]))]))
        )
    }

    /// Order is part of the format: reordering keys changes the hash.
    func testObjectKeysKeepInsertionOrderAndAreNotSorted() {
        XCTAssertEqual(
            #"{"b":1,"a":2}"#,
            Cj.write(Cj.obj([("b", Cj.num(1)), ("a", Cj.num(2))]))
        )
    }

    func testStringsEscapeExactlyTheDocumentedSet() {
        let raw = "a\"b\\c\u{08}\u{09}\u{0a}\u{0c}\u{0d}é😀"
        XCTAssertEqual(
            "{\"s\":\"a\\\"b\\\\c\\b\\t\\n\\f\\ré😀\"}",
            Cj.write(Cj.obj([("s", .str(raw))])),
            "short escapes for the five named controls; text and emoji stay literal"
        )
    }

    /// Other controls become lowercase \u00xx, and only those.
    func testOtherControlCharactersUseLowercaseFourDigitEscapes() {
        XCTAssertEqual(
            "{\"s\":\"\\u0001\\u001f\"}",
            Cj.write(Cj.obj([("s", .str("\u{01}\u{1f}"))]))
        )
    }

    func testIntegersCarryNoExponentOrTrailingZero() {
        XCTAssertEqual(#"{"n":7}"#, Cj.write(Cj.obj([("n", Cj.num(Int64(7)))])))
        XCTAssertEqual(#"{"n":0}"#, Cj.write(Cj.obj([("n", Cj.num(0))])))
    }

    /// The only fractions the protocol carries are plain ones like this.
    func testDoublesKeepATrailingPointZeroWhenIntegral() {
        XCTAssertEqual(#"{"d":0.5}"#, Cj.write(Cj.obj([("d", Cj.num(0.5))])))
        XCTAssertEqual(#"{"d":1.0}"#, Cj.write(Cj.obj([("d", Cj.num(1.0))])))
    }

    /// Absent is not null: a null-valued field is dropped, never emitted.
    func testNullFieldsAreOmittedEntirely() {
        XCTAssertEqual(
            #"{"a":1}"#,
            Cj.write(Cj.obj([("a", Cj.num(1)), ("b", nil)]))
        )
    }
}
