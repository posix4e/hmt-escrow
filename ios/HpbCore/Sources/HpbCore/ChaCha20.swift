import Foundation

/// Raw RFC 8439 ChaCha20 (counter starts at 0). CryptoKit only ships the
/// AEAD construction; NIP-44 needs the bare stream cipher, validated here
/// by the official NIP-44 vectors.
enum ChaCha20 {
    static func xor(key: [UInt8], nonce12: [UInt8], data: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(data.count)
        var counter: UInt32 = 0
        var offset = 0
        while offset < data.count {
            let block = block(key: key, nonce: nonce12, counter: counter)
            let n = min(64, data.count - offset)
            for i in 0..<n { out.append(data[offset + i] ^ block[i]) }
            offset += n
            counter &+= 1
        }
        return out
    }

    private static func word(_ bytes: [UInt8], _ i: Int) -> UInt32 {
        UInt32(bytes[i]) | UInt32(bytes[i + 1]) << 8 |
            UInt32(bytes[i + 2]) << 16 | UInt32(bytes[i + 3]) << 24
    }

    private static func block(key: [UInt8], nonce: [UInt8], counter: UInt32) -> [UInt8] {
        var state: [UInt32] = [
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
            word(key, 0), word(key, 4), word(key, 8), word(key, 12),
            word(key, 16), word(key, 20), word(key, 24), word(key, 28),
            counter, word(nonce, 0), word(nonce, 4), word(nonce, 8),
        ]
        var working = state
        for _ in 0..<10 {
            quarter(&working, 0, 4, 8, 12)
            quarter(&working, 1, 5, 9, 13)
            quarter(&working, 2, 6, 10, 14)
            quarter(&working, 3, 7, 11, 15)
            quarter(&working, 0, 5, 10, 15)
            quarter(&working, 1, 6, 11, 12)
            quarter(&working, 2, 7, 8, 13)
            quarter(&working, 3, 4, 9, 14)
        }
        for i in 0..<16 { state[i] = state[i] &+ working[i] }
        var out = [UInt8]()
        out.reserveCapacity(64)
        for value in state {
            out.append(UInt8(truncatingIfNeeded: value))
            out.append(UInt8(truncatingIfNeeded: value >> 8))
            out.append(UInt8(truncatingIfNeeded: value >> 16))
            out.append(UInt8(truncatingIfNeeded: value >> 24))
        }
        return out
    }

    private static func quarter(_ s: inout [UInt32], _ a: Int, _ b: Int, _ c: Int, _ d: Int) {
        s[a] = s[a] &+ s[b]; s[d] = rotl(s[d] ^ s[a], 16)
        s[c] = s[c] &+ s[d]; s[b] = rotl(s[b] ^ s[c], 12)
        s[a] = s[a] &+ s[b]; s[d] = rotl(s[d] ^ s[a], 8)
        s[c] = s[c] &+ s[d]; s[b] = rotl(s[b] ^ s[c], 7)
    }

    private static func rotl(_ value: UInt32, _ by: UInt32) -> UInt32 {
        (value << by) | (value >> (32 - by))
    }
}
