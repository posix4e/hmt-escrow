import Foundation

public struct Claim {
    public let offerEventId: String
    public let escrowId: String
    public let payoutAddress: String
    public let attestationEventIds: [String]

    public init(offerEventId: String, escrowId: String, payoutAddress: String, attestationEventIds: [String]) {
        self.offerEventId = offerEventId
        self.escrowId = escrowId
        self.payoutAddress = payoutAddress
        self.attestationEventIds = attestationEventIds
    }
}

public struct Grant {
    public let claimEventId: String
    public let escrowId: String
    public let granted: Bool
    public let taskKeys: [String]
    public let expiresAt: Int64
    public let reason: String?

    public init(
        claimEventId: String, escrowId: String, granted: Bool,
        taskKeys: [String], expiresAt: Int64, reason: String? = nil
    ) {
        self.claimEventId = claimEventId
        self.escrowId = escrowId
        self.granted = granted
        self.taskKeys = taskKeys
        self.expiresAt = expiresAt
        self.reason = reason
    }
}

public struct Answer {
    public let taskKey: String
    public let answer: String

    public init(taskKey: String, answer: String) {
        self.taskKey = taskKey
        self.answer = answer
    }
}

public struct Submission {
    public let grantEventId: String
    public let escrowId: String
    public let answers: [Answer]

    public init(grantEventId: String, escrowId: String, answers: [Answer]) {
        self.grantEventId = grantEventId
        self.escrowId = escrowId
        self.answers = answers
    }
}

/// Claim/grant/resign/submission codecs. The grant is the countersignature
/// that resolves claim races: only the offer author's grants allocate work.
public enum Assignments {
    public static func claim(
        _ privkey: [UInt8], launcher: String, _ c: Claim, createdAt: Int64
    ) throws -> NostrEvent {
        try Events.sign(
            privkey, kind: ProtocolKinds.claim,
            tags: [
                ["e", c.offerEventId],
                ["x", c.escrowId],
                ["p", launcher],
            ],
            content: Cj.write(Cj.obj([
                ("v", Cj.num(ProtocolKinds.version)),
                ("payout_address", .str(c.payoutAddress)),
                ("attestations", .arr(c.attestationEventIds.map(J.str))),
            ])),
            createdAt: createdAt
        )
    }

    public static func parseClaim(_ event: NostrEvent) throws -> Claim {
        guard event.kind == ProtocolKinds.claim else { throw HpbError.invalid("not a claim") }
        let content = try Cj.parse(event.content)
        guard let offerEventId = event.tagValue("e"), let escrowId = event.tagValue("x") else {
            throw HpbError.invalid("claim missing tags")
        }
        return Claim(
            offerEventId: offerEventId,
            escrowId: escrowId,
            payoutAddress: try content.s("payout_address"),
            attestationEventIds: try content.a("attestations").compactMap(\.stringValue)
        )
    }

    public static func grant(
        _ privkey: [UInt8], worker: String, _ g: Grant, createdAt: Int64
    ) throws -> NostrEvent {
        try Events.sign(
            privkey, kind: ProtocolKinds.grant,
            tags: [
                ["e", g.claimEventId],
                ["x", g.escrowId],
                ["p", worker],
            ],
            content: Cj.write(Cj.obj([
                ("v", Cj.num(ProtocolKinds.version)),
                ("status", .str(g.granted ? "granted" : "rejected")),
                ("task_keys", .arr(g.taskKeys.map(J.str))),
                ("expires_at", Cj.num(g.expiresAt)),
                ("reason", g.reason.map(J.str)),
            ])),
            createdAt: createdAt
        )
    }

    public static func parseGrant(_ event: NostrEvent) throws -> Grant {
        guard event.kind == ProtocolKinds.grant else { throw HpbError.invalid("not a grant") }
        let content = try Cj.parse(event.content)
        guard let claimEventId = event.tagValue("e"), let escrowId = event.tagValue("x") else {
            throw HpbError.invalid("grant missing tags")
        }
        return Grant(
            claimEventId: claimEventId,
            escrowId: escrowId,
            granted: try content.s("status") == "granted",
            taskKeys: try content.a("task_keys").compactMap(\.stringValue),
            expiresAt: try content.l("expires_at"),
            reason: content.sOrNull("reason")
        )
    }

    public static func resign(
        _ privkey: [UInt8], launcher: String, grantEventId: String, escrowId: String, createdAt: Int64
    ) throws -> NostrEvent {
        try Events.sign(
            privkey, kind: ProtocolKinds.resign,
            tags: [
                ["e", grantEventId],
                ["x", escrowId],
                ["p", launcher],
            ],
            content: Cj.write(Cj.obj([("v", Cj.num(ProtocolKinds.version))])),
            createdAt: createdAt
        )
    }

    /// Answers are NIP-44 encrypted to the validator (copy-protection AND
    /// commitment). The nonce is random in production; the fixed-nonce
    /// override exists ONLY so the vector corpus is byte-stable.
    public static func submission(
        _ privkey: [UInt8], validatorPubkey: String, _ s: Submission,
        createdAt: Int64, nonce: [UInt8]? = nil
    ) throws -> NostrEvent {
        let plaintext = Cj.write(Cj.obj([
            ("v", Cj.num(ProtocolKinds.version)),
            ("answers", .arr(s.answers.map {
                Cj.obj([("task_key", .str($0.taskKey)), ("answer", .str($0.answer))])
            })),
        ]))
        let key = try Nip44.conversationKey(privkey, validatorPubkey.hexBytes())
        return try Events.sign(
            privkey, kind: ProtocolKinds.submission,
            tags: [
                ["e", s.grantEventId],
                ["x", s.escrowId],
                ["p", validatorPubkey],
            ],
            content: try Nip44.encrypt(plaintext, key, nonce ?? randomNonce()),
            createdAt: createdAt
        )
    }

    private static func randomNonce() -> [UInt8] {
        (0..<32).map { _ in UInt8.random(in: 0...255) }
    }
}
