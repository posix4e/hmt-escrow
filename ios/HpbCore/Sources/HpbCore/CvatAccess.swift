import Foundation

/// What a worker tells the launcher: the CVAT identity it wants admitted.
public struct CvatAccessRequest: Equatable {
    public let claimEventId: String
    public let escrowId: String
    public let cvatEmail: String

    public init(claimEventId: String, escrowId: String, cvatEmail: String) {
        self.claimEventId = claimEventId
        self.escrowId = escrowId
        self.cvatEmail = cvatEmail
    }
}

/// What the launcher tells the worker back. `invitationKey` is the secret half.
public struct CvatAccessGrant: Equatable {
    public let grantEventId: String
    public let escrowId: String
    public let workerPubkey: String
    public let cvatId: Int64
    public let baseUrl: String
    public let orgSlug: String
    public let invitationKey: String
}

/// A worker's signed statement of what its own annotations hashed to.
public struct CvatCommitment: Equatable {
    public let escrowId: String
    public let taskKey: String
    public let worker: String
    public let annotationsSha256: String

    public init(escrowId: String, taskKey: String, worker: String, annotationsSha256: String) {
        self.escrowId = escrowId
        self.taskKey = taskKey
        self.worker = worker
        self.annotationsSha256 = annotationsSha256
    }
}

/// The Swift half of the CVAT access handshake — the mirror of
/// `kotlin/protocol/src/main/kotlin/org/hpb/protocol/CvatAccess.kt`.
///
/// It rides beside the claim and the grant rather than inside them, because
/// those are closed shapes in the byte-locked vector corpus. The invitation key
/// is NIP-44 encrypted to the worker; the CVAT user id rides as a public tag,
/// since NIP-44 covers content and never tags, and every witness needs it.
public enum CvatAccessCodec {
    public static func request(
        _ privkey: [UInt8],
        launcherPubkey: String,
        _ r: CvatAccessRequest,
        createdAt: Int64,
        nonce: [UInt8] = randomNonce()
    ) throws -> NostrEvent {
        let plaintext = Cj.write(Cj.obj([
            ("v", Cj.num(ProtocolKinds.version)),
            ("cvat_email", .str(r.cvatEmail)),
        ]))
        let key = try Nip44.conversationKey(privkey, try launcherPubkey.hexBytes())
        return try Events.sign(
            privkey, kind: ProtocolKinds.cvatAccessRequest,
            tags: [
                ["e", r.claimEventId],
                ["x", r.escrowId],
                ["p", launcherPubkey],
            ],
            content: try Nip44.encrypt(plaintext, key, nonce),
            createdAt: createdAt
        )
    }

    /// The worker's view: decrypts the invitation key it needs in order to join.
    public static func parseGrant(_ event: NostrEvent, workerPrivkey: [UInt8]) throws -> CvatAccessGrant {
        guard let binding = binding(event) else { throw HpbError.invalid("not a cvat access grant") }
        let key = try Nip44.conversationKey(workerPrivkey, try event.pubkey.hexBytes())
        let content = try Cj.parse(try Nip44.decrypt(event.content, key))
        return CvatAccessGrant(
            grantEventId: binding.grantEventId,
            escrowId: binding.escrowId,
            workerPubkey: binding.workerPubkey,
            cvatId: binding.cvatId,
            baseUrl: try content.s("base_url"),
            orgSlug: try content.s("org"),
            invitationKey: try content.s("invitation_key")
        )
    }

    /// Any observer's view: the identity binding alone, no key required.
    public static func binding(_ event: NostrEvent) -> CvatBinding? {
        guard event.kind == ProtocolKinds.cvatAccessGrant,
              let grantEventId = event.tagValue("e"),
              let escrowId = event.tagValue("x"),
              let worker = event.tagValue("p"),
              let raw = event.tagValue("cvat_id"),
              let cvatId = Int64(raw)
        else { return nil }
        return CvatBinding(
            grantEventId: grantEventId,
            escrowId: escrowId,
            workerPubkey: worker,
            cvatId: cvatId
        )
    }

    public static func randomNonce() -> [UInt8] {
        (0..<32).map { _ in UInt8.random(in: 0...255) }
    }
}

/// The public half of an access grant: which Nostr worker is which CVAT user.
public struct CvatBinding: Equatable {
    public let grantEventId: String
    public let escrowId: String
    public let workerPubkey: String
    public let cvatId: Int64
}

/// The commitment is public on purpose: submissions are encrypted to the
/// launcher, so only a public hash lets a witness — which administers nothing —
/// hold the reveal to what the worker actually did.
public enum CvatCommitments {
    public static func toEvent(
        _ privkey: [UInt8], _ commitment: CvatCommitment, createdAt: Int64
    ) throws -> NostrEvent {
        try Events.sign(
            privkey, kind: ProtocolKinds.cvatCommitment,
            tags: [
                ["x", commitment.escrowId],
                ["k", commitment.taskKey],
                ["h", commitment.annotationsSha256],
            ],
            content: Cj.write(Cj.obj([("v", Cj.num(ProtocolKinds.version))])),
            createdAt: createdAt
        )
    }

    public static func fromEvent(_ event: NostrEvent) -> CvatCommitment? {
        guard event.kind == ProtocolKinds.cvatCommitment,
              let escrowId = event.tagValue("x"),
              let taskKey = event.tagValue("k"),
              let hash = event.tagValue("h")
        else { return nil }
        return CvatCommitment(
            escrowId: escrowId, taskKey: taskKey, worker: event.pubkey, annotationsSha256: hash
        )
    }
}
