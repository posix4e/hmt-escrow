import Foundation

/// One revealed, validated answer in the final results set.
public struct ResultRow: Equatable {
    public let taskKey: String
    public let worker: String
    public let answer: String
    public let accepted: Bool

    public init(taskKey: String, worker: String, answer: String, accepted: Bool) {
        self.taskKey = taskKey
        self.worker = worker
        self.answer = answer
        self.accepted = accepted
    }
}

/// The launcher's reveal: the full validated submission set, INLINE; its
/// sha256 is the results hash committed in the on-chain PAYOUT record.
public struct EscrowResults {
    public let escrowId: String
    public let rows: [ResultRow]

    public init(escrowId: String, rows: [ResultRow]) {
        self.escrowId = escrowId
        self.rows = rows
    }

    public func resultsJson() -> String {
        Cj.write(Cj.obj([
            ("v", Cj.num(ProtocolKinds.version)),
            ("rows", .arr(rows.map {
                Cj.obj([
                    ("task_key", .str($0.taskKey)),
                    ("worker", .str($0.worker)),
                    ("answer", .str($0.answer)),
                    ("accepted", .bool($0.accepted)),
                ])
            })),
        ]))
    }

    public func resultsHash() -> [UInt8] {
        sha256(resultsJson())
    }
}

public enum Validations {
    public static func toEvent(_ privkey: [UInt8], _ results: EscrowResults, createdAt: Int64) throws -> NostrEvent {
        try Events.sign(
            privkey, kind: ProtocolKinds.validation,
            tags: [["x", results.escrowId]],
            content: results.resultsJson(),
            createdAt: createdAt
        )
    }

    public static func fromEvent(_ event: NostrEvent) throws -> EscrowResults {
        guard event.kind == ProtocolKinds.validation else { throw HpbError.invalid("not a validation") }
        let content = try Cj.parse(event.content)
        guard let escrowId = event.tagValue("x") else { throw HpbError.invalid("validation missing x tag") }
        return EscrowResults(
            escrowId: escrowId,
            rows: try content.a("rows").map {
                ResultRow(
                    taskKey: try $0.s("task_key"),
                    worker: try $0.s("worker"),
                    answer: try $0.s("answer"),
                    accepted: try $0.b("accepted")
                )
            }
        )
    }
}
