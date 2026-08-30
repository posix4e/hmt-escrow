import Foundation

/// Canonical JSON for the protocol's hand-built contents — the Swift twin
/// of the Kotlin side's kotlinx compact form. Two things carry the
/// byte-parity burden: object keys keep INSERTION order, and strings escape
/// exactly the set kotlinx escapes (`"` `\` and control characters, with
/// \b \t \n \f \r shortcuts) — event ids are hashes of this output.
public indirect enum J {
    case str(String)
    case raw(String) // number literal, emitted verbatim
    case bool(Bool)
    case null
    case arr([J])
    case obj([(String, J)])
}

public enum Cj {
    public static func num(_ value: Int64) -> J { .raw(String(value)) }
    public static func num(_ value: Int) -> J { .raw(String(value)) }
    /// Swift and Kotlin agree on shortest-roundtrip doubles with a kept
    /// ".0" for integral values — the protocol only ever carries plain
    /// fractions like 0.5 here.
    public static func num(_ value: Double) -> J { .raw("\(value)") }

    public static func obj(_ pairs: [(String, J?)]) -> J {
        .obj(pairs.compactMap { key, value in value.map { (key, $0) } })
    }

    public static func write(_ value: J) -> String {
        var out = ""
        append(value, to: &out)
        return out
    }

    private static func append(_ value: J, to out: inout String) {
        switch value {
        case .str(let s): appendQuoted(s, to: &out)
        case .raw(let r): out += r
        case .bool(let b): out += b ? "true" : "false"
        case .null: out += "null"
        case .arr(let items):
            out += "["
            for (i, item) in items.enumerated() {
                if i > 0 { out += "," }
                append(item, to: &out)
            }
            out += "]"
        case .obj(let pairs):
            out += "{"
            for (i, pair) in pairs.enumerated() {
                if i > 0 { out += "," }
                appendQuoted(pair.0, to: &out)
                out += ":"
                append(pair.1, to: &out)
            }
            out += "}"
        }
    }

    private static func appendQuoted(_ s: String, to out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{09}": out += "\\t"
            case "\u{0a}": out += "\\n"
            case "\u{0c}": out += "\\f"
            case "\u{0d}": out += "\\r"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }

    public static func parse(_ text: String) throws -> J {
        var parser = Parser(text)
        let value = try parser.value()
        parser.skipWhitespace()
        guard parser.atEnd else { throw HpbError.invalid("trailing json") }
        return value
    }
}

/// Accessors mirroring the Kotlin side's Pj: primitives expose their raw
/// content, so type sloppiness in malformed relay data fails (or passes)
/// identically in both implementations.
public extension J {
    private func pairs() throws -> [(String, J)] {
        guard case .obj(let pairs) = self else { throw HpbError.invalid("not an object") }
        return pairs
    }

    private func member(_ key: String) throws -> J {
        guard let found = try pairs().first(where: { $0.0 == key }) else {
            throw HpbError.invalid("missing \(key)")
        }
        return found.1
    }

    private func content() throws -> String {
        switch self {
        case .str(let s): return s
        case .raw(let r): return r
        case .bool(let b): return b ? "true" : "false"
        case .null: return "null"
        default: throw HpbError.invalid("not a primitive")
        }
    }

    func s(_ key: String) throws -> String { try member(key).content() }

    func sOrNull(_ key: String) -> String? {
        guard let found = try? member(key) else { return nil }
        if case .null = found { return nil }
        return try? found.content()
    }

    func l(_ key: String) throws -> Int64 {
        guard let value = Int64(try member(key).content()) else {
            throw HpbError.invalid("not a long: \(key)")
        }
        return value
    }

    func i(_ key: String) throws -> Int {
        guard let value = Int(try member(key).content()) else {
            throw HpbError.invalid("not an int: \(key)")
        }
        return value
    }

    func d(_ key: String) throws -> Double {
        guard let value = Double(try member(key).content()) else {
            throw HpbError.invalid("not a double: \(key)")
        }
        return value
    }

    func b(_ key: String) throws -> Bool {
        switch try member(key).content() {
        case "true": return true
        case "false": return false
        default: throw HpbError.invalid("not a bool: \(key)")
        }
    }

    func a(_ key: String) throws -> [J] {
        guard case .arr(let items) = try member(key) else {
            throw HpbError.invalid("not an array: \(key)")
        }
        return items
    }

    func o(_ key: String) throws -> J {
        let found = try member(key)
        guard case .obj = found else { throw HpbError.invalid("not an object: \(key)") }
        return found
    }

    var stringValue: String? {
        try? content()
    }
}

/// A small recursive-descent JSON parser that keeps object order and raw
/// number literals — Foundation's parser preserves neither.
private struct Parser {
    private let scalars: [Unicode.Scalar]
    private var pos = 0

    init(_ text: String) {
        scalars = Array(text.unicodeScalars)
    }

    var atEnd: Bool { pos >= scalars.count }

    mutating func skipWhitespace() {
        while pos < scalars.count, " \t\n\r".unicodeScalars.contains(scalars[pos]) { pos += 1 }
    }

    private mutating func expect(_ scalar: Unicode.Scalar) throws {
        guard pos < scalars.count, scalars[pos] == scalar else {
            throw HpbError.invalid("expected \(scalar)")
        }
        pos += 1
    }

    mutating func value() throws -> J {
        skipWhitespace()
        guard pos < scalars.count else { throw HpbError.invalid("truncated json") }
        switch scalars[pos] {
        case "{": return try object()
        case "[": return try array()
        case "\"": return .str(try string())
        case "t": try literal("true"); return .bool(true)
        case "f": try literal("false"); return .bool(false)
        case "n": try literal("null"); return .null
        default: return .raw(try number())
        }
    }

    private mutating func literal(_ text: String) throws {
        for scalar in text.unicodeScalars { try expect(scalar) }
    }

    private mutating func object() throws -> J {
        try expect("{")
        var pairs = [(String, J)]()
        skipWhitespace()
        if pos < scalars.count, scalars[pos] == "}" { pos += 1; return .obj(pairs) }
        while true {
            skipWhitespace()
            let key = try string()
            skipWhitespace()
            try expect(":")
            pairs.append((key, try value()))
            skipWhitespace()
            guard pos < scalars.count else { throw HpbError.invalid("truncated object") }
            if scalars[pos] == "," { pos += 1; continue }
            try expect("}")
            return .obj(pairs)
        }
    }

    private mutating func array() throws -> J {
        try expect("[")
        var items = [J]()
        skipWhitespace()
        if pos < scalars.count, scalars[pos] == "]" { pos += 1; return .arr(items) }
        while true {
            items.append(try value())
            skipWhitespace()
            guard pos < scalars.count else { throw HpbError.invalid("truncated array") }
            if scalars[pos] == "," { pos += 1; continue }
            try expect("]")
            return .arr(items)
        }
    }

    private mutating func string() throws -> String {
        try expect("\"")
        var units = [UInt16]()
        while true {
            guard pos < scalars.count else { throw HpbError.invalid("truncated string") }
            let scalar = scalars[pos]
            pos += 1
            if scalar == "\"" { break }
            if scalar == "\\" {
                units.append(contentsOf: try escape())
            } else {
                units.append(contentsOf: Array(String(Character(scalar)).utf16))
            }
        }
        guard let text = String(utf16CodeUnits: units, count: units.count) as String? else {
            throw HpbError.invalid("bad string")
        }
        return text
    }

    private mutating func escape() throws -> [UInt16] {
        guard pos < scalars.count else { throw HpbError.invalid("truncated escape") }
        let scalar = scalars[pos]
        pos += 1
        switch scalar {
        case "\"": return [0x22]
        case "\\": return [0x5c]
        case "/": return [0x2f]
        case "b": return [0x08]
        case "t": return [0x09]
        case "n": return [0x0a]
        case "f": return [0x0c]
        case "r": return [0x0d]
        case "u": return [try hex4()]
        default: throw HpbError.invalid("bad escape")
        }
    }

    private mutating func hex4() throws -> UInt16 {
        guard pos + 4 <= scalars.count else { throw HpbError.invalid("truncated \\u") }
        let text = String(String.UnicodeScalarView(scalars[pos..<pos + 4]))
        guard let unit = UInt16(text, radix: 16) else { throw HpbError.invalid("bad \\u") }
        pos += 4
        return unit
    }

    private mutating func number() throws -> String {
        let start = pos
        let allowed = Set("-+.eE0123456789".unicodeScalars)
        while pos < scalars.count, allowed.contains(scalars[pos]) { pos += 1 }
        guard pos > start else { throw HpbError.invalid("bad number") }
        return String(String.UnicodeScalarView(scalars[start..<pos]))
    }
}
