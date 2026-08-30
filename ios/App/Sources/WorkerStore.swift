import Foundation
import HpbCore
import SwiftUI

/// The app's whole state: a thin observable view over HpbCore's
/// WorkerSession, polled — the worker's world is still just a key and
/// relays. `--demo` swaps in an in-memory job so the UI (and the CI
/// screenshot test) runs without network.
@MainActor
final class WorkerStore: ObservableObject {
    struct TaskModel: Identifiable {
        let key: String
        let text: String
        let imageData: Data?
        let choices: [String]
        var id: String { key }
    }

    struct JobModel: Identifiable {
        let escrowId: String
        let jobType: String
        let rewardPerTaskSats: Int64
        var status: String
        let tasks: [TaskModel]
        var id: String { escrowId }
    }

    struct EarningModel: Identifiable {
        let escrowId: String
        let txid: String
        let sats: Int64
        var id: String { escrowId + txid }
    }

    @Published var jobs = [JobModel]()
    @Published var earnings = [EarningModel]()
    @Published var pubkey = ""
    @Published var networkLabel = "nostr + bitcoin"
    @Published var picks = [String: [String: String]]()
    @Published var lastError: String?

    @AppStorage("payoutAddress") var payoutAddress = ""
    @AppStorage("relays") var relayList = ""

    private let demo: Bool
    private var session: WorkerSession?
    private var jobRows = [String: WorkerSession.JobRow]()
    private var poller: Task<Void, Never>?

    init(demo: Bool = CommandLine.arguments.contains("--demo")) {
        self.demo = demo
        if demo {
            DemoData.install(into: self)
        } else {
            startSession()
        }
    }

    private func startSession() {
        let relays = relayList.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !relays.isEmpty else {
            lastError = "add a relay above to find work"
            return
        }
        do {
            let session = try WorkerSession(
                relays: RelayClient(relays: relays),
                privkey: try WorkerKey.load()
            )
            self.session = session
            pubkey = session.pubkey
            lastError = nil
            poller?.cancel()
            poller = Task { [weak self] in
                while !Task.isCancelled {
                    await self?.refresh()
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                }
            }
        } catch {
            lastError = "worker key unavailable: \(error)"
        }
    }

    func relaysChanged() {
        if !demo { startSession() }
    }

    func refresh() async {
        guard let session else { return }
        let rows = await session.openJobs()
        var models = [JobModel]()
        for row in rows {
            jobRows[row.offer.escrowId] = row
            let mine = await session.assignments(row).first
            // a grant can cover a subset of the offer's tasks — only the
            // granted keys are this worker's to label and submit
            let granted = Set(mine?.taskKeys ?? [])
            let tasks = row.offer.tasks.filter { granted.isEmpty || granted.contains($0.key) }
            models.append(JobModel(
                escrowId: row.offer.escrowId,
                jobType: row.offer.jobType,
                rewardPerTaskSats: row.offer.rewardPerTaskSats,
                status: statusName(mine?.status),
                tasks: tasks.map(Self.taskModel)
            ))
        }
        jobs = models.sorted { $0.escrowId < $1.escrowId }
        earnings = await session.earnings().map {
            EarningModel(escrowId: $0.escrowId, txid: $0.txid, sats: $0.sats)
        }
    }

    private func statusName(_ status: AssignmentStatus?) -> String {
        guard let status else { return "open" }
        switch status {
        case .claimed: return "claimed"
        case .active: return "active"
        default: return status.rawValue.lowercased()
        }
    }

    /// App-level task convention (protocol untouched): a question that
    /// parses as {text, image?, choices?} renders an image card with
    /// label buttons; plain text falls back to a free-text field.
    static func taskModel(_ task: TaskItem) -> TaskModel {
        guard let parsed = try? Cj.parse(task.question) else {
            return TaskModel(key: task.key, text: task.question, imageData: nil, choices: [])
        }
        let text = parsed.sOrNull("text") ?? ""
        let image = parsed.sOrNull("image")
        let choices = (try? parsed.a("choices"))?.compactMap(\.stringValue) ?? []
        guard !text.isEmpty || image != nil else {
            return TaskModel(key: task.key, text: task.question, imageData: nil, choices: [])
        }
        var imageData: Data?
        if let image, image.hasPrefix("data:image/"),
           let base64 = image.split(separator: ",").dropFirst().first {
            imageData = Data(base64Encoded: String(base64))
        }
        return TaskModel(key: task.key, text: text, imageData: imageData, choices: choices)
    }

    func pick(_ job: JobModel, _ task: TaskModel, _ answer: String) {
        picks[job.escrowId, default: [:]][task.key] = answer
    }

    func claim(_ job: JobModel) {
        if demo {
            DemoData.claim(store: self, escrowId: job.escrowId)
            return
        }
        guard !payoutAddress.isEmpty else {
            lastError = "enter a payout address first"
            return
        }
        guard let row = jobRows[job.escrowId], let session else { return }
        Task {
            do {
                let badges = await session.attestations(for: row.offer)
                if row.offer.kyc.required, badges.isEmpty {
                    lastError = "this job requires a KYC badge from an accepted attester"
                    return
                }
                try await session.claim(row, payoutAddress: payoutAddress, attestationIds: badges)
                lastError = nil
                await refresh()
            } catch {
                lastError = "claim failed: \(error)"
            }
        }
    }

    func submit(_ job: JobModel) {
        // only answers for the tasks this worker was actually granted
        let granted = Set(job.tasks.map(\.id))
        let answers = (picks[job.escrowId] ?? [:]).filter { granted.contains($0.key) }
        if demo {
            DemoData.submit(store: self, escrowId: job.escrowId)
            return
        }
        guard let row = jobRows[job.escrowId], let session else { return }
        Task {
            do {
                guard let active = await session.assignments(row)
                    .first(where: { $0.status == .active }) else {
                    lastError = "no active assignment for this job"
                    return
                }
                try await session.submit(
                    row, active,
                    answers.map { Answer(taskKey: $0.key, answer: $0.value) }
                )
                lastError = nil
                await refresh()
            } catch {
                lastError = "submit failed: \(error)"
            }
        }
    }
}

/// The worker key lives in the keychain; generated on first run.
enum WorkerKey {
    static func load() throws -> [UInt8] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.hpb.labeler",
            kSecAttrAccount as String: "worker-key",
            kSecReturnData as String: true,
        ]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data, data.count == 32 {
            return Array(data)
        }
        var key = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, key.count, &key) == errSecSuccess else {
            throw HpbError.invalid("no entropy")
        }
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.hpb.labeler",
            kSecAttrAccount as String: "worker-key",
            kSecValueData as String: Data(key),
        ]
        SecItemAdd(add as CFDictionary, nil)
        return key
    }
}
