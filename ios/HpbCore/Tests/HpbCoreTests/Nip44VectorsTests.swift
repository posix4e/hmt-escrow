import XCTest
@testable import HpbCore

/// The official NIP-44 v2 test vectors — the same file the Kotlin
/// reference pins (docs/vectors/nip44.vectors.json).
final class Nip44VectorsTests: XCTestCase {
    private var valid: J!
    private var invalid: J!

    override func setUpWithError() throws {
        let text = try String(contentsOf: vectorsUrl("nip44.vectors.json"), encoding: .utf8)
        let v2 = try Cj.parse(text).o("v2")
        valid = try v2.o("valid")
        invalid = try v2.o("invalid")
    }

    func testConversationKeys() throws {
        for caseJson in try valid.a("get_conversation_key") {
            let key = try Nip44.conversationKey(
                try caseJson.s("sec1").hexBytes(),
                try caseJson.s("pub2").hexBytes()
            )
            XCTAssertEqual(try caseJson.s("conversation_key"), key.hex)
        }
    }

    func testMessageKeys() throws {
        let section = try valid.o("get_message_keys")
        let conversationKey = try section.s("conversation_key").hexBytes()
        for caseJson in try section.a("keys") {
            let keys = Nip44.messageKeys(conversationKey, try caseJson.s("nonce").hexBytes())
            XCTAssertEqual(try caseJson.s("chacha_key"), keys.chachaKey.hex)
            XCTAssertEqual(try caseJson.s("chacha_nonce"), keys.chachaNonce.hex)
            XCTAssertEqual(try caseJson.s("hmac_key"), keys.hmacKey.hex)
        }
    }

    func testPaddedLengths() throws {
        for caseJson in try valid.a("calc_padded_len") {
            guard case .arr(let pair) = caseJson, pair.count == 2,
                  let unpadded = pair[0].stringValue.flatMap({ Int($0) }),
                  let expected = pair[1].stringValue.flatMap({ Int($0) }) else {
                XCTFail("bad calc_padded_len case")
                continue
            }
            XCTAssertEqual(expected, try Nip44.paddedLength(unpadded))
        }
    }

    func testEncryptDecryptRoundTrips() throws {
        for caseJson in try valid.a("encrypt_decrypt") {
            let sec1 = try caseJson.s("sec1").hexBytes()
            let sec2 = try caseJson.s("sec2").hexBytes()
            let conversationKey = try caseJson.s("conversation_key").hexBytes()
            XCTAssertEqual(
                conversationKey.hex,
                try Nip44.conversationKey(sec1, Secp.xonly(sec2)).hex,
                "conversation key derivation"
            )
            let payload = try Nip44.encrypt(
                try caseJson.s("plaintext"),
                conversationKey,
                try caseJson.s("nonce").hexBytes()
            )
            XCTAssertEqual(try caseJson.s("payload"), payload)
            XCTAssertEqual(try caseJson.s("plaintext"), try Nip44.decrypt(payload, conversationKey))
        }
    }

    func testInvalidDecryptsFail() throws {
        for caseJson in try invalid.a("decrypt") {
            let note = try caseJson.s("note")
            XCTAssertThrowsError(
                try Nip44.decrypt(
                    try caseJson.s("payload"),
                    try caseJson.s("conversation_key").hexBytes()
                ),
                note
            )
        }
    }

    func testInvalidConversationKeysFail() throws {
        for caseJson in try invalid.a("get_conversation_key") {
            XCTAssertThrowsError(
                try Nip44.conversationKey(
                    try caseJson.s("sec1").hexBytes(),
                    try caseJson.s("pub2").hexBytes()
                ),
                caseJson.sOrNull("note") ?? "invalid key"
            )
        }
    }

    func testEventSignVerifyRoundTrip() throws {
        var key = [UInt8](repeating: 0, count: 32)
        key[31] = 7
        let event = try Events.sign(
            key, kind: 30078, tags: [["d", "test"]], content: "hello", createdAt: 42
        )
        XCTAssertTrue(Events.verify(event))
        let tampered = NostrEvent(
            id: event.id, pubkey: event.pubkey, createdAt: event.createdAt,
            kind: event.kind, tags: event.tags, content: "tampered", sig: event.sig
        )
        XCTAssertFalse(Events.verify(tampered))
        let badSig = NostrEvent(
            id: event.id, pubkey: event.pubkey, createdAt: event.createdAt,
            kind: event.kind, tags: event.tags, content: event.content,
            sig: String(repeating: "00", count: 64)
        )
        XCTAssertFalse(Events.verify(badSig))
    }
}
