import Foundation

/// Mechanical validation: pure functions over the revealed submission set.
/// Any observer recomputes both acceptance and the payout list.
public enum Validators {
    /// Kotlin's trim() predicate (Java Character.isWhitespace) — notably it
    /// does NOT trim no-break spaces, unlike Foundation's whitespace set.
    private static func isRefWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09...0x0d, 0x1c...0x1f: return true
        case 0xa0, 0x2007, 0x202f: return false
        default:
            switch scalar.properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }

    public static func normalize(_ answer: String) -> String {
        var scalars = Array(answer.unicodeScalars)
        while let first = scalars.first, isRefWhitespace(first) { scalars.removeFirst() }
        while let last = scalars.last, isRefWhitespace(last) { scalars.removeLast() }
        return String(String.UnicodeScalarView(scalars)).lowercased()
    }

    /// The key a tally is grouped by — never the native `String`.
    ///
    /// Swift's `String` equality is canonical equivalence, so `café` written NFC
    /// and NFD compares equal here and collapses to one dictionary key, while
    /// Kotlin's UTF-16 code-unit equality sees two. Two witnesses would compute
    /// different winners and different payouts from identical data. Hex of the
    /// normalized UTF-8 bytes is ASCII, so equality means the same thing on both
    /// sides and depends on neither one's Unicode version.
    public static func labelKey(_ answer: String) -> String {
        Array(normalize(answer).utf8).hex
    }

    /// The commitment format for groundtruth sets: sha256("key:normalized").
    public static func groundtruthHash(_ taskKey: String, _ answer: String) -> String {
        sha256("\(taskKey):\(normalize(answer))").hex
    }

    public struct Submitted {
        public let taskKey: String
        public let worker: String
        public let answer: String

        public init(taskKey: String, worker: String, answer: String) {
            self.taskKey = taskKey
            self.worker = worker
            self.answer = answer
        }
    }

    private static let granted: Set<AssignmentStatus> = [.active, .submitted, .validated]

    /// Grant-scoping, applied by the launcher when collecting AND
    /// re-checked by every witness against the reveal: a row counts only
    /// when its worker holds a granted assignment covering the task, and
    /// only once per (worker, task) — first occurrence wins.
    public static func scoped(_ rows: [Submitted], _ assignments: [AssignmentState]) -> [Submitted] {
        var grantedKeys = [String: Set<String>]()
        for state in assignments where granted.contains(state.status) {
            grantedKeys[state.worker] = Set(state.taskKeys)
        }
        var seen = Set<String>()
        return rows.filter { row in
            grantedKeys[row.worker]?.contains(row.taskKey) == true &&
                seen.insert("\(row.worker)|\(row.taskKey)").inserted
        }
    }

    public static func validate(_ policy: ValidationPolicy, _ submissions: [Submitted]) -> [ResultRow] {
        switch policy.type {
        case .groundtruth:
            return submissions.map {
                ResultRow(
                    taskKey: $0.taskKey, worker: $0.worker, answer: $0.answer,
                    accepted: policy.groundtruthHashes.contains(groundtruthHash($0.taskKey, $0.answer))
                )
            }
        case .agreement:
            return agreement(policy, submissions)
        }
    }

    /// Inter-worker consensus: per task, the modal normalized answer wins
    /// when it reaches ceil(n * threshold); deterministic tie-break by the
    /// lexicographically smallest modal answer.
    private static func agreement(_ policy: ValidationPolicy, _ submissions: [Submitted]) -> [ResultRow] {
        var perTask = [String: [Submitted]]()
        for sub in submissions { perTask[sub.taskKey, default: []].append(sub) }
        let winners = perTask.mapValues { winningAnswer(policy, $0) }
        return submissions.map {
            ResultRow(
                taskKey: $0.taskKey, worker: $0.worker, answer: $0.answer,
                accepted: winners[$0.taskKey].flatMap { $0 } == labelKey($0.answer)
            )
        }
    }

    private static func winningAnswer(_ policy: ValidationPolicy, _ subs: [Submitted]) -> String? {
        var counts = [String: Int]()
        // Tallied by key, tie-broken on the label: hex of UTF-8 does not order
        // the same as UTF-16 above ASCII, and utf16Less is the shared rule.
        var labels = [String: String]()
        for sub in subs {
            let key = labelKey(sub.answer)
            counts[key, default: 0] += 1
            labels[key] = normalize(sub.answer)
        }
        let best = counts.sorted {
            $0.value != $1.value ? $0.value > $1.value
                : utf16Less(labels[$0.key] ?? $0.key, labels[$1.key] ?? $1.key)
        }[0]
        let needed = max(1, Int(ceil(Double(subs.count) * policy.agreementThreshold)))
        return best.value >= needed ? best.key : nil
    }

    /// The deterministic payout list: reward per accepted answer,
    /// aggregated per worker, ordered by worker pubkey.
    public static func payouts(
        _ rewardPerTaskSats: Int64,
        _ rows: [ResultRow],
        _ payoutAddressOf: (String) -> String
    ) -> [PayoutLine] {
        var accepted = [String: Int64]()
        for row in rows where row.accepted { accepted[row.worker, default: 0] += 1 }
        return accepted.keys.sorted(by: utf16Less).map { worker in
            PayoutLine(
                worker: worker,
                address: payoutAddressOf(worker),
                sats: rewardPerTaskSats * accepted[worker]!
            )
        }
    }
}
