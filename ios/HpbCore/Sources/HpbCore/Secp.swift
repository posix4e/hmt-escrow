import Foundation
import secp256k1

/// Thin wrapper over libsecp256k1 for the worker's needs: x-only keys,
/// BIP340 schnorr (Nostr event ids), and the raw ECDH x-coordinate NIP-44
/// wants. All library API use is isolated here.
public enum Secp {
    public static func xonly(_ privkey: [UInt8]) throws -> [UInt8] {
        try secp256k1.Signing.PrivateKey(dataRepresentation: privkey).publicKey.xonly.bytes
    }

    public static func xonlyHex(_ privkey: [UInt8]) throws -> String {
        try xonly(privkey).hex
    }

    /// Deterministic BIP340 signature: zeroed aux randomness is what
    /// libsecp uses for a NULL aux pointer, so signatures match the Kotlin
    /// reference (ACINQ passes null) byte-for-byte — the vector corpus
    /// depends on that.
    public static func schnorrSign(_ message32: [UInt8], _ privkey: [UInt8]) throws -> [UInt8] {
        var message = message32
        var aux = [UInt8](repeating: 0, count: 32)
        let key = try secp256k1.Schnorr.PrivateKey(dataRepresentation: privkey)
        return try Array(key.signature(message: &message, auxiliaryRand: &aux).dataRepresentation)
    }

    public static func schnorrVerify(
        _ signature: [UInt8],
        _ message32: [UInt8],
        _ xonly: [UInt8]
    ) -> Bool {
        guard signature.count == 64, message32.count == 32, xonly.count == 32 else { return false }
        guard let sig = try? secp256k1.Schnorr.SchnorrSignature(dataRepresentation: signature) else {
            return false
        }
        var message = message32
        return secp256k1.Schnorr.XonlyKey(dataRepresentation: xonly).isValid(sig, for: &message)
    }

    /// Raw X coordinate of the ECDH shared point (NIP-44 needs it
    /// unhashed); throws on an invalid peer key or an out-of-range privkey.
    public static func ecdhX(_ privkey: [UInt8], _ peerXonly: [UInt8]) throws -> [UInt8] {
        guard privkey.count == 32, peerXonly.count == 32 else {
            throw HpbError.invalid("bad key length")
        }
        var pubkey = secp256k1_pubkey()
        let compressed = [UInt8]([0x02] + peerXonly)
        guard secp256k1_ec_pubkey_parse(
            secp256k1.Context.rawRepresentation, &pubkey, compressed, compressed.count
        ) == 1 else {
            throw HpbError.invalid("invalid x-only key")
        }
        var shared = [UInt8](repeating: 0, count: 32)
        guard secp256k1_ecdh(
            secp256k1.Context.rawRepresentation, &shared, &pubkey, privkey,
            { output, x32, _, _ in
                memcpy(output, x32, 32)
                return 1
            }, nil
        ) == 1 else {
            throw HpbError.invalid("invalid private key")
        }
        return shared
    }
}
