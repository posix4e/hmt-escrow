import CryptoKit
import Foundation

public enum HpbError: Error {
    case invalid(String)
}

public func sha256(_ data: [UInt8]) -> [UInt8] {
    Array(CryptoKit.SHA256.hash(data: Data(data)))
}

public func sha256(_ text: String) -> [UInt8] {
    sha256(Array(text.utf8))
}

public extension Array where Element == UInt8 {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

public extension String {
    func hexBytes() throws -> [UInt8] {
        guard count % 2 == 0 else { throw HpbError.invalid("odd-length hex") }
        var out = [UInt8]()
        out.reserveCapacity(count / 2)
        var index = startIndex
        while index < endIndex {
            let next = self.index(index, offsetBy: 2)
            guard let byte = UInt8(self[index..<next], radix: 16) else {
                throw HpbError.invalid("bad hex")
            }
            out.append(byte)
            index = next
        }
        return out
    }
}

/// Kotlin/Java string ordering (UTF-16 code units) — every deterministic
/// sort in the protocol must agree with the reference implementation.
public func utf16Less(_ a: String, _ b: String) -> Bool {
    var ai = a.utf16.makeIterator()
    var bi = b.utf16.makeIterator()
    while true {
        switch (ai.next(), bi.next()) {
        case (nil, nil): return false
        case (nil, _): return true
        case (_, nil): return false
        case let (x?, y?):
            if x != y { return x < y }
        }
    }
}
