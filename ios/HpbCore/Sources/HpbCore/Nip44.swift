import CryptoKit
import Foundation

/// NIP-44 v2 encryption (the protocol's channel for submissions),
/// validated against the official vendored test vectors — the same file
/// the Kotlin reference pins.
public enum Nip44 {
    private static let version: UInt8 = 2
    private static let minLen = 1
    private static let maxLen = 65535

    private static func hmacSha256(_ key: [UInt8], _ chunks: [[UInt8]]) -> [UInt8] {
        var mac = HMAC<CryptoKit.SHA256>(key: SymmetricKey(data: Data(key)))
        for chunk in chunks { mac.update(data: Data(chunk)) }
        return Array(mac.finalize())
    }

    static func hkdfExtract(salt: [UInt8], ikm: [UInt8]) -> [UInt8] {
        hmacSha256(salt, [ikm])
    }

    static func hkdfExpand(prk: [UInt8], info: [UInt8], length: Int) -> [UInt8] {
        var out = [UInt8]()
        var block = [UInt8]()
        var counter: UInt8 = 1
        while out.count < length {
            block = hmacSha256(prk, [block, info, [counter]])
            out.append(contentsOf: block)
            counter &+= 1
        }
        return Array(out.prefix(length))
    }

    /// Shared conversation key for (our privkey, their x-only pubkey).
    public static func conversationKey(_ privkey: [UInt8], _ peerXonly: [UInt8]) throws -> [UInt8] {
        hkdfExtract(salt: Array("nip44-v2".utf8), ikm: try Secp.ecdhX(privkey, peerXonly))
    }

    struct MessageKeys {
        let chachaKey: [UInt8]
        let chachaNonce: [UInt8]
        let hmacKey: [UInt8]
    }

    static func messageKeys(_ conversationKey: [UInt8], _ nonce: [UInt8]) -> MessageKeys {
        let expanded = hkdfExpand(prk: conversationKey, info: nonce, length: 76)
        return MessageKeys(
            chachaKey: Array(expanded[0..<32]),
            chachaNonce: Array(expanded[32..<44]),
            hmacKey: Array(expanded[44..<76])
        )
    }

    static func paddedLength(_ unpadded: Int) throws -> Int {
        guard unpadded >= minLen else { throw HpbError.invalid("empty plaintext") }
        if unpadded <= 32 { return 32 }
        var nextPower = 1
        while nextPower < unpadded { nextPower <<= 1 }
        let chunk = nextPower <= 256 ? 32 : nextPower / 8
        return chunk * ((unpadded - 1) / chunk + 1)
    }

    private static func pad(_ plaintext: [UInt8]) throws -> [UInt8] {
        guard plaintext.count >= minLen, plaintext.count <= maxLen else {
            throw HpbError.invalid("invalid plaintext length")
        }
        var padded = [UInt8](repeating: 0, count: 2 + (try paddedLength(plaintext.count)))
        padded[0] = UInt8(plaintext.count >> 8)
        padded[1] = UInt8(plaintext.count & 0xff)
        for (i, byte) in plaintext.enumerated() { padded[2 + i] = byte }
        return padded
    }

    private static func unpad(_ padded: [UInt8]) throws -> [UInt8] {
        guard padded.count >= 2 else { throw HpbError.invalid("invalid padding") }
        let length = Int(padded[0]) << 8 | Int(padded[1])
        guard length >= minLen, length <= maxLen,
              padded.count == 2 + (try paddedLength(length)) else {
            throw HpbError.invalid("invalid padding")
        }
        return Array(padded[2..<2 + length])
    }

    public static func encrypt(
        _ plaintext: String,
        _ conversationKey: [UInt8],
        _ nonce: [UInt8]
    ) throws -> String {
        let keys = messageKeys(conversationKey, nonce)
        let ciphertext = ChaCha20.xor(
            key: keys.chachaKey, nonce12: keys.chachaNonce,
            data: try pad(Array(plaintext.utf8))
        )
        let mac = hmacSha256(keys.hmacKey, [nonce, ciphertext])
        return Data([version] + nonce + ciphertext + mac).base64EncodedString()
    }

    public static func decrypt(_ payload: String, _ conversationKey: [UInt8]) throws -> String {
        guard !payload.hasPrefix("#") else { throw HpbError.invalid("unsupported future version") }
        guard let data = Data(base64Encoded: payload) else {
            throw HpbError.invalid("invalid base64")
        }
        let raw = Array(data)
        guard raw.count >= 1 + 32 + 32 + 34, raw[0] == version else {
            throw HpbError.invalid("invalid payload")
        }
        let nonce = Array(raw[1..<33])
        let ciphertext = Array(raw[33..<raw.count - 32])
        let mac = Array(raw[(raw.count - 32)...])
        let keys = messageKeys(conversationKey, nonce)
        guard hmacSha256(keys.hmacKey, [nonce, ciphertext]) == mac else {
            throw HpbError.invalid("invalid MAC")
        }
        let plain = try unpad(ChaCha20.xor(key: keys.chachaKey, nonce12: keys.chachaNonce, data: ciphertext))
        guard let text = String(bytes: plain, encoding: .utf8) else {
            throw HpbError.invalid("invalid utf8")
        }
        return text
    }
}
