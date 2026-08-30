import Foundation

/// A NIP-01 event; identity keys are the same secp256k1 x-only keys as the
/// escrow descriptors.
public struct NostrEvent: Equatable, Sendable {
    public let id: String
    public let pubkey: String
    public let createdAt: Int64
    public let kind: Int
    public let tags: [[String]]
    public let content: String
    public let sig: String

    public init(
        id: String, pubkey: String, createdAt: Int64, kind: Int,
        tags: [[String]], content: String, sig: String
    ) {
        self.id = id
        self.pubkey = pubkey
        self.createdAt = createdAt
        self.kind = kind
        self.tags = tags
        self.content = content
        self.sig = sig
    }

    public func tagValue(_ name: String) -> String? {
        tags.first { $0.count >= 2 && $0[0] == name }?[1]
    }
}

public enum Events {
    private static func serializeForId(
        pubkey: String, createdAt: Int64, kind: Int, tags: [[String]], content: String
    ) -> String {
        Cj.write(.arr([
            Cj.num(0),
            .str(pubkey),
            Cj.num(createdAt),
            Cj.num(kind),
            .arr(tags.map { tag in .arr(tag.map(J.str)) }),
            .str(content),
        ]))
    }

    public static func sign(
        _ privkey: [UInt8], kind: Int, tags: [[String]], content: String, createdAt: Int64
    ) throws -> NostrEvent {
        let pubkey = try Secp.xonlyHex(privkey)
        let id = sha256(serializeForId(
            pubkey: pubkey, createdAt: createdAt, kind: kind, tags: tags, content: content
        ))
        return NostrEvent(
            id: id.hex,
            pubkey: pubkey,
            createdAt: createdAt,
            kind: kind,
            tags: tags,
            content: content,
            sig: try Secp.schnorrSign(id, privkey).hex
        )
    }

    public static func verify(_ event: NostrEvent) -> Bool {
        let id = sha256(serializeForId(
            pubkey: event.pubkey, createdAt: event.createdAt, kind: event.kind,
            tags: event.tags, content: event.content
        ))
        guard id.hex == event.id else { return false }
        guard let sig = try? event.sig.hexBytes(),
              let xonly = try? event.pubkey.hexBytes() else { return false }
        return Secp.schnorrVerify(sig, id, xonly)
    }

    public static func toJson(_ event: NostrEvent) -> J {
        .obj([
            ("id", .str(event.id)),
            ("pubkey", .str(event.pubkey)),
            ("created_at", Cj.num(event.createdAt)),
            ("kind", Cj.num(event.kind)),
            ("tags", .arr(event.tags.map { tag in .arr(tag.map(J.str)) })),
            ("content", .str(event.content)),
            ("sig", .str(event.sig)),
        ])
    }

    public static func fromJson(_ json: J) throws -> NostrEvent {
        NostrEvent(
            id: try json.s("id"),
            pubkey: try json.s("pubkey"),
            createdAt: try json.l("created_at"),
            kind: try json.i("kind"),
            tags: try json.a("tags").map { tag in
                guard case .arr(let items) = tag else { throw HpbError.invalid("bad tag") }
                return try items.map {
                    guard let value = $0.stringValue else { throw HpbError.invalid("bad tag") }
                    return value
                }
            },
            content: try json.s("content"),
            sig: try json.s("sig")
        )
    }
}
