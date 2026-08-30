import Foundation

public struct NostrFilter {
    public var kinds: [Int]?
    public var authors: [String]?
    public var ids: [String]?
    public var dTag: String?
    public var xTag: String?
    public var pTag: String?
    public var eTag: String?
    public var limit: Int?

    public init(
        kinds: [Int]? = nil, authors: [String]? = nil, ids: [String]? = nil,
        dTag: String? = nil, xTag: String? = nil, pTag: String? = nil,
        eTag: String? = nil, limit: Int? = nil
    ) {
        self.kinds = kinds
        self.authors = authors
        self.ids = ids
        self.dTag = dTag
        self.xTag = xTag
        self.pTag = pTag
        self.eTag = eTag
        self.limit = limit
    }

    public func toJson() -> J {
        var pairs = [(String, J)]()
        if let kinds { pairs.append(("kinds", .arr(kinds.map(Cj.num)))) }
        if let authors { pairs.append(("authors", .arr(authors.map(J.str)))) }
        if let ids { pairs.append(("ids", .arr(ids.map(J.str)))) }
        for (name, value) in [("#d", dTag), ("#x", xTag), ("#p", pTag), ("#e", eTag)] {
            if let value { pairs.append((name, .arr([.str(value)]))) }
        }
        if let limit { pairs.append(("limit", Cj.num(limit))) }
        return .obj(pairs)
    }
}

/// The phone's relay client: same NIP-01 semantics as the Kotlin clients —
/// publish everywhere, read the verified, deduplicated union. Each call is
/// a one-shot connection (REQ … EOSE, or EVENT … OK), which keeps state off
/// the socket and matches the app's poll-driven use.
public final class RelayClient {
    private let urls: [String]

    public init(relays: [String]) {
        precondition(!relays.isEmpty, "at least one relay required")
        urls = relays
    }

    public func publish(_ event: NostrEvent, timeoutSeconds: Double = 5) async -> Bool {
        var accepted = false
        for url in urls {
            if await publishTo(url, event, timeoutSeconds) { accepted = true }
        }
        return accepted
    }

    public func fetch(_ filter: NostrFilter, timeoutSeconds: Double = 5) async -> [NostrEvent] {
        var seen = Set<String>()
        var out = [NostrEvent]()
        for url in urls {
            for event in await fetchFrom(url, filter, timeoutSeconds)
            where seen.insert(event.id).inserted {
                out.append(event)
            }
        }
        return out
    }

    private func publishTo(_ url: String, _ event: NostrEvent, _ timeout: Double) async -> Bool {
        guard let socketUrl = URL(string: url) else { return false }
        let task = URLSession.shared.webSocketTask(with: socketUrl)
        task.resume()
        defer { task.cancel(with: .normalClosure, reason: nil) }
        let message = Cj.write(.arr([.str("EVENT"), Events.toJson(event)]))
        let awaitAck: @Sendable () async throws -> Bool = {
            while true {
                guard let reply = try await self.receiveText(task),
                      case .arr(let parts) = try Cj.parse(reply),
                      parts.count >= 3 else { continue }
                if parts[0].stringValue == "OK", parts[1].stringValue == event.id {
                    return parts[2].stringValue == "true"
                }
            }
        }
        do {
            try await task.send(.string(message))
            return try await withDeadline(timeout, awaitAck) ?? false
        } catch {
            return false
        }
    }

    private func fetchFrom(_ url: String, _ filter: NostrFilter, _ timeout: Double) async -> [NostrEvent] {
        guard let socketUrl = URL(string: url) else { return [] }
        let task = URLSession.shared.webSocketTask(with: socketUrl)
        task.resume()
        defer { task.cancel(with: .normalClosure, reason: nil) }
        let subId = UUID().uuidString.lowercased().prefix(16)
        let request = Cj.write(.arr([.str("REQ"), .str(String(subId)), filter.toJson()]))
        let collectUntilEose: @Sendable () async throws -> [NostrEvent] = {
            var collected = [NostrEvent]()
            while true {
                guard let reply = try await self.receiveText(task),
                      case .arr(let parts) = try Cj.parse(reply),
                      parts.count >= 2 else { continue }
                switch parts[0].stringValue ?? "" {
                case "EVENT":
                    if parts.count >= 3, let event = try? Events.fromJson(parts[2]) {
                        collected.append(event)
                    }
                case "EOSE":
                    return collected
                default:
                    break
                }
            }
        }
        do {
            try await task.send(.string(request))
            let events = try await withDeadline(timeout, collectUntilEose) ?? []
            try? await task.send(.string(Cj.write(.arr([.str("CLOSE"), .str(String(subId))]))))
            return events.filter { Events.verify($0) }
        } catch {
            return []
        }
    }

    private func receiveText(_ task: URLSessionWebSocketTask) async throws -> String? {
        switch try await task.receive() {
        case .string(let text): return text
        case .data(let data): return String(data: data, encoding: .utf8)
        @unknown default: return nil
        }
    }

    /// Runs the operation with a wall-clock deadline; nil on timeout.
    private func withDeadline<T: Sendable>(
        _ seconds: Double,
        _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T? {
        try await withThrowingTaskGroup(of: T?.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let first = try await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
