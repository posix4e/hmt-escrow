import Foundation

/// The worker's whole app state, derived from relays alone — no backend,
/// no chain access (receipts carry txids the wallet layer verifies
/// itself). The Swift twin of androidcore's WorkerSession, so the SwiftUI
/// shell stays a thin view over this.
public final class WorkerSession {
    private let relays: RelayClient
    private let privkey: [UInt8]
    public let pubkey: String

    public init(relays: RelayClient, privkey: [UInt8]) throws {
        self.relays = relays
        self.privkey = privkey
        pubkey = try Secp.xonlyHex(privkey)
    }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970) }

    public struct JobRow {
        public let event: NostrEvent
        public let offer: JobOffer
    }

    public func openJobs() async -> [JobRow] {
        let events = await relays.fetch(NostrFilter(kinds: [ProtocolKinds.jobOffer], limit: 100))
        return events.compactMap { event in
            guard let offer = try? Offers.fromEvent(event) else { return nil }
            return JobRow(event: event, offer: offer)
        }.filter { $0.offer.status == "open" && $0.offer.expiresAt > now() }
    }

    /// My attestation event ids usable for this offer's KYC policy —
    /// verified with the same fail-closed check the launcher applies, so
    /// the claim only carries badges that can actually satisfy the grant.
    public func attestations(for offer: JobOffer) async -> [String] {
        guard offer.kyc.required else { return [] }
        let events = await relays.fetch(
            NostrFilter(kinds: [ProtocolKinds.attestation], pTag: pubkey)
        )
        return events
            .filter { Attestations.satisfies($0, policy: offer.kyc, worker: pubkey, now: now()) }
            .map(\.id)
    }

    @discardableResult
    public func claim(
        _ job: JobRow, payoutAddress: String, attestationIds: [String]
    ) async throws -> NostrEvent {
        let event = try Assignments.claim(
            privkey, launcher: job.event.pubkey,
            Claim(
                offerEventId: job.event.id, escrowId: job.offer.escrowId,
                payoutAddress: payoutAddress, attestationEventIds: attestationIds
            ),
            createdAt: now()
        )
        guard await relays.publish(event) else { throw HpbError.invalid("claim publish failed") }
        return event
    }

    /// My assignments across a job, via the shared deterministic reducer.
    public func assignments(_ job: JobRow) async -> [AssignmentState] {
        let related = await relays.fetch(NostrFilter(xTag: job.offer.escrowId, limit: 500))
        return Reducer.reduce(offerEvent: job.event, events: related, now: now())
            .filter { $0.worker == pubkey }
    }

    @discardableResult
    public func submit(
        _ job: JobRow, _ assignment: AssignmentState, _ answers: [Answer]
    ) async throws -> NostrEvent {
        guard let grantId = assignment.grantEventId else {
            throw HpbError.invalid("assignment not granted")
        }
        let event = try Assignments.submission(
            privkey, validatorPubkey: job.event.pubkey,
            Submission(grantEventId: grantId, escrowId: job.offer.escrowId, answers: answers),
            createdAt: now()
        )
        guard await relays.publish(event) else { throw HpbError.invalid("submission publish failed") }
        return event
    }

    public struct Earning {
        public let escrowId: String
        public let txid: String
        public let sats: Int64
    }

    /// Receipts naming me — but a receipt counts only when its author is a
    /// launcher I actually claimed that escrow with: my own signed claims
    /// bind (escrowId, launcher), so a stranger's well-formed receipt
    /// naming me cannot show up as earnings. The wallet layer still
    /// spot-verifies txids on-chain.
    public func earnings() async -> [Earning] {
        let myClaims = await relays.fetch(
            NostrFilter(kinds: [ProtocolKinds.claim], authors: [pubkey], limit: 500)
        )
        var claimedLaunchers = [String: Set<String>]()
        for claim in myClaims {
            guard let escrowId = claim.tagValue("x"), let launcher = claim.tagValue("p") else {
                continue
            }
            claimedLaunchers[escrowId, default: []].insert(launcher)
        }
        let events = await relays.fetch(NostrFilter(kinds: [ProtocolKinds.receipt], pTag: pubkey))
        return events.compactMap { event in
            guard let receipt = try? Receipts.fromEvent(event),
                  claimedLaunchers[receipt.escrowId]?.contains(event.pubkey) == true,
                  let line = receipt.lines.first(where: { $0.worker == pubkey }) else { return nil }
            return Earning(escrowId: receipt.escrowId, txid: receipt.txid, sats: line.sats)
        }
    }
}
