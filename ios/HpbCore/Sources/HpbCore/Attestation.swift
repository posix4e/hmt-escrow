import Foundation

/// Portable KYC/qualification attestations. KYC is OPTIONAL at the job
/// level; attesters compete — no single gatekeeper.
public struct Attestation {
    public let schema: String
    public let subject: String
    public let status: String // "valid" | "revoked"
    public let issuedAt: Int64
    public let validUntil: Int64

    public init(schema: String, subject: String, status: String, issuedAt: Int64, validUntil: Int64) {
        self.schema = schema
        self.subject = subject
        self.status = status
        self.issuedAt = issuedAt
        self.validUntil = validUntil
    }
}

public enum Attestations {
    public static func toEvent(_ attesterPrivkey: [UInt8], _ a: Attestation, createdAt: Int64) throws -> NostrEvent {
        try Events.sign(
            attesterPrivkey, kind: ProtocolKinds.attestation,
            tags: [
                ["d", "\(a.schema):\(a.subject)"],
                ["p", a.subject],
                ["t", a.schema],
            ],
            content: Cj.write(Cj.obj([
                ("v", Cj.num(ProtocolKinds.version)),
                ("schema", .str(a.schema)),
                ("subject", .str(a.subject)),
                ("status", .str(a.status)),
                ("issued_at", Cj.num(a.issuedAt)),
                ("valid_until", Cj.num(a.validUntil)),
            ])),
            createdAt: createdAt
        )
    }

    public static func fromEvent(_ event: NostrEvent) throws -> Attestation {
        guard event.kind == ProtocolKinds.attestation else { throw HpbError.invalid("not an attestation") }
        let content = try Cj.parse(event.content)
        return Attestation(
            schema: try content.s("schema"),
            subject: try content.s("subject"),
            status: try content.s("status"),
            issuedAt: try content.l("issued_at"),
            validUntil: try content.l("valid_until")
        )
    }

    /// Does this event satisfy an offer's KYC policy for the worker, now?
    /// Fails closed on any gap.
    public static func satisfies(_ event: NostrEvent, policy: KycPolicy, worker: String, now: Int64) -> Bool {
        if !policy.required { return true }
        guard isAcceptedAttester(event, policy) else { return false }
        guard let attestation = try? fromEvent(event) else { return false }
        return attestation.subject == worker &&
            attestation.status == "valid" &&
            now < attestation.validUntil
    }

    private static func isAcceptedAttester(_ event: NostrEvent, _ policy: KycPolicy) -> Bool {
        Events.verify(event) &&
            event.kind == ProtocolKinds.attestation &&
            policy.attesters.contains(event.pubkey)
    }
}
