import Foundation

public enum AssignmentStatus: String {
    case claimed = "CLAIMED"
    case active = "ACTIVE"
    case rejected = "REJECTED"
    case resigned = "RESIGNED"
    case submitted = "SUBMITTED"
    case validated = "VALIDATED"
    case expired = "EXPIRED"
}

public struct AssignmentState {
    public let claimEventId: String
    public let worker: String
    public var status: AssignmentStatus
    public var grantEventId: String?
    public var taskKeys: [String]
    public var expiresAt: Int64?
    public let payoutAddress: String
}

/// The deterministic assignment reducer — every party derives identical
/// state from the event set alone. Ordering: causal phase first (claims,
/// then grants, then the worker moves a grant enables, then reveals),
/// created_at ascending + event-id tie-break within a phase; causally
/// chained events routinely share a second, so a bare (created_at, id)
/// sort would silently drop a grant sorting before its claim. Authority:
/// only the offer author's grants allocate; only the worker's own
/// resign/submission move its assignment. Wrong-party events are IGNORED.
public enum Reducer {
    private static let phase: [Int: Int] = [
        ProtocolKinds.claim: 0,
        ProtocolKinds.grant: 1,
        ProtocolKinds.resign: 2,
        ProtocolKinds.submission: 2,
        ProtocolKinds.validation: 3,
    ]

    private static let launcherKinds: Set<Int> = [ProtocolKinds.grant, ProtocolKinds.validation]

    public static func reduce(
        offerEvent: NostrEvent, events: [NostrEvent], now: Int64
    ) -> [AssignmentState] {
        let ordered = events.filter(Events.verify).sorted { a, b in
            let pa = phase[a.kind] ?? 0
            let pb = phase[b.kind] ?? 0
            if pa != pb { return pa < pb }
            if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
            return utf16Less(a.id, b.id)
        }
        var states = OrderedStates()
        for event in ordered {
            apply(&states, offerEvent: offerEvent, launcher: offerEvent.pubkey, event: event)
        }
        return states.values.map { expire($0, now: now) }
    }

    /// Insertion-ordered map, like the reference's LinkedHashMap.
    private struct OrderedStates {
        private(set) var order = [String]()
        private var byId = [String: AssignmentState]()

        var values: [AssignmentState] { order.map { byId[$0]! } }

        func contains(_ id: String) -> Bool { byId[id] != nil }

        subscript(id: String) -> AssignmentState? {
            get { byId[id] }
            set {
                if byId[id] == nil, newValue != nil { order.append(id) }
                byId[id] = newValue
            }
        }

        func first(where predicate: (AssignmentState) -> Bool) -> AssignmentState? {
            values.first(where: predicate)
        }

        mutating func replaceAll(_ transform: (AssignmentState) -> AssignmentState) {
            for id in order { byId[id] = transform(byId[id]!) }
        }
    }

    private static func apply(
        _ states: inout OrderedStates, offerEvent: NostrEvent, launcher: String, event: NostrEvent
    ) {
        if launcherKinds.contains(event.kind), event.pubkey != launcher { return }
        switch event.kind {
        case ProtocolKinds.claim: applyClaim(&states, offerEvent: offerEvent, event: event)
        case ProtocolKinds.grant: applyGrant(&states, event: event)
        case ProtocolKinds.resign: applyResign(&states, event: event)
        case ProtocolKinds.submission: applySubmission(&states, event: event)
        case ProtocolKinds.validation: applyValidation(&states, event: event)
        default: break
        }
    }

    private static func applyClaim(_ states: inout OrderedStates, offerEvent: NostrEvent, event: NostrEvent) {
        guard let claim = try? Assignments.parseClaim(event) else { return }
        guard claim.offerEventId == offerEvent.id, !states.contains(event.id) else { return }
        states[event.id] = AssignmentState(
            claimEventId: event.id,
            worker: event.pubkey,
            status: .claimed,
            grantEventId: nil,
            taskKeys: [],
            expiresAt: nil,
            payoutAddress: claim.payoutAddress
        )
    }

    private static func applyGrant(_ states: inout OrderedStates, event: NostrEvent) {
        guard let grant = try? Assignments.parseGrant(event) else { return }
        guard var state = states[grant.claimEventId], state.status == .claimed else { return }
        state.status = grant.granted ? .active : .rejected
        state.grantEventId = event.id
        state.taskKeys = grant.taskKeys
        state.expiresAt = grant.expiresAt
        states[grant.claimEventId] = state
    }

    private static func applyResign(_ states: inout OrderedStates, event: NostrEvent) {
        guard var state = states.first(where: { $0.grantEventId == event.tagValue("e") }) else { return }
        guard event.pubkey == state.worker, state.status == .active else { return }
        state.status = .resigned
        states[state.claimEventId] = state
    }

    private static func applySubmission(_ states: inout OrderedStates, event: NostrEvent) {
        guard var state = states.first(where: { $0.grantEventId == event.tagValue("e") }) else { return }
        guard event.pubkey == state.worker, state.status == .active else { return }
        state.status = .submitted
        states[state.claimEventId] = state
    }

    /// The escrow-results reveal marks every submitted assignment validated.
    private static func applyValidation(_ states: inout OrderedStates, event: NostrEvent) {
        guard let results = try? Validations.fromEvent(event) else { return }
        let workers = Set(results.rows.map(\.worker))
        states.replaceAll { state in
            guard state.status == .submitted, workers.contains(state.worker) else { return state }
            var updated = state
            updated.status = .validated
            return updated
        }
    }

    /// Wall-clock expiry, evaluated lazily at read time.
    private static func expire(_ state: AssignmentState, now: Int64) -> AssignmentState {
        guard state.status == .active, let expiresAt = state.expiresAt, now > expiresAt else {
            return state
        }
        var expired = state
        expired.status = .expired
        return expired
    }
}
