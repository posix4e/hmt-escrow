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
        /// Set when the work lives in CVAT rather than in the offer.
        let work: CvatWorkSource?
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

    /// Escrows this worker has actually been admitted to in CVAT. Until the
    /// launcher answers the access request there is nothing to open, so the
    /// card must not pretend otherwise.
    @Published var cvatReady = Set<String>()

    /// The worker's own CVAT session, injected so the launcher never has it.
    var cvat: CvatStore?

    @AppStorage("payoutAddress") var payoutAddress = ""
    @AppStorage("relays") var relayList = ""

    private let demo: Bool
    private var session: WorkerSession?
    private var relayClient: RelayClient?
    private var privkey: [UInt8]?
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
            let client = RelayClient(relays: relays)
            let key = try WorkerKey.load()
            let session = try WorkerSession(relays: client, privkey: key)
            self.session = session
            self.relayClient = client
            self.privkey = key
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
        let external = jobs.filter { job in job.tasks.contains { $0.work != nil } }
        if !external.isEmpty { await syncCvatAccess(external) }
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
        if let work = ExternalWork.workSource(task.question) {
            let text = (try? Cj.parse(task.question))?.sOrNull("text") ?? task.question
            return TaskModel(key: task.key, text: text, imageData: nil, choices: [], work: work)
        }
        guard let parsed = try? Cj.parse(task.question) else {
            return TaskModel(key: task.key, text: task.question, imageData: nil, choices: [], work: nil)
        }
        let text = parsed.sOrNull("text") ?? ""
        let image = parsed.sOrNull("image")
        let choices = (try? parsed.a("choices"))?.compactMap(\.stringValue) ?? []
        guard !text.isEmpty || image != nil else {
            return TaskModel(key: task.key, text: task.question, imageData: nil, choices: [], work: nil)
        }
        var imageData: Data?
        if let image, image.hasPrefix("data:image/"),
           let base64 = image.split(separator: ",").dropFirst().first {
            imageData = Data(base64Encoded: String(base64))
        }
        return TaskModel(key: task.key, text: text, imageData: imageData, choices: choices, work: nil)
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
                let claim = try await session.claim(
                    row, payoutAddress: payoutAddress, attestationIds: badges
                )
                try await requestCvatAccess(job, row, claimEventId: claim.id)
                lastError = nil
                await refresh()
            } catch {
                lastError = "claim failed: \(error)"
            }
        }
    }

    /// Pick up access grants and join the organization they invite us to.
    ///
    /// Accepting is idempotent, and for an account that already existed CVAT
    /// accepts on creation — so this is usually just bookkeeping that turns the
    /// "Open in CVAT" button on.
    private func syncCvatAccess(_ external: [JobModel]) async {
        guard let relayClient, let privkey, let cvat, !pubkey.isEmpty else { return }
        await cvat.ensureIdentity()
        let events = await relayClient.fetch(
            NostrFilter(kinds: [ProtocolKinds.cvatAccessGrant], pTag: pubkey, limit: 50)
        )
        for event in events {
            guard let grant = try? CvatAccessCodec.parseGrant(event, workerPrivkey: privkey),
                  !cvatReady.contains(grant.escrowId) else { continue }
            do {
                try await cvat.accept(invitationKey: grant.invitationKey)
                cvatReady.insert(grant.escrowId)
            } catch {
                lastError = "CVAT access not usable yet: \(error)"
            }
        }
        for job in external where !cvatReady.contains(job.escrowId) {
            await ensureAccessRequested(job)
        }
    }

    /// Re-send the access request for a job that has none.
    ///
    /// Claiming and asking for access are two events, so they can come apart —
    /// a signed-out CVAT account at claim time, a dropped publish, an app
    /// updated between the two. Rather than stranding the worker with a job it
    /// can never open, this notices and asks again.
    private func ensureAccessRequested(_ job: JobModel) async {
        guard let relayClient, let row = jobRows[job.escrowId] else { return }
        let asked = await relayClient.fetch(
            NostrFilter(
                kinds: [ProtocolKinds.cvatAccessRequest], authors: [pubkey],
                xTag: job.escrowId, limit: 10
            )
        )
        guard asked.isEmpty else { return }
        let claims = await relayClient.fetch(
            NostrFilter(
                kinds: [ProtocolKinds.claim], authors: [pubkey],
                xTag: job.escrowId, limit: 10
            )
        )
        guard let claim = claims.first else { return }
        do {
            try await requestCvatAccess(job, row, claimEventId: claim.id)
        } catch {
            // Surfaced, not swallowed: a silent failure here strands the worker
            // on a job it can never open.
            lastError = "could not ask for CVAT access: \(error)"
        }
    }

    /// Ask the launcher to admit this worker's own CVAT account.
    ///
    /// Sent alongside the claim rather than inside it: the claim is a closed
    /// shape in the byte-locked corpus. The address is NIP-44 encrypted to the
    /// launcher, and it is all the launcher ever learns — the password and
    /// token stay on this device.
    private func requestCvatAccess(
        _ job: JobModel, _ row: WorkerSession.JobRow, claimEventId: String
    ) async throws {
        guard job.tasks.contains(where: { $0.work != nil }) else { return }
        guard let cvat, let privkey, let relayClient else {
            throw CvatError.http(0, "sign in to CVAT before claiming this job")
        }
        guard !cvat.email.isEmpty else {
            throw CvatError.http(0, "set your CVAT email so the launcher can invite you")
        }
        let request = try CvatAccessCodec.request(
            privkey,
            launcherPubkey: row.event.pubkey,
            CvatAccessRequest(
                claimEventId: claimEventId,
                escrowId: job.escrowId,
                cvatEmail: cvat.email
            ),
            createdAt: Int64(Date().timeIntervalSince1970)
        )
        guard await relayClient.publish(request) else {
            throw CvatError.http(0, "could not publish the CVAT access request")
        }
    }

    /// For work done in CVAT the answer is not a label but a commitment.
    ///
    /// The app reads back *your own* annotations with *your own* CVAT token,
    /// hashes them, and publishes that hash publicly before submitting. That
    /// public hash is what lets a witness refuse a reveal you never made — it
    /// is the reason the launcher, which runs CVAT, cannot put words in your
    /// mouth.
    private func externalAnswers(
        _ job: JobModel, _ assignment: AssignmentState
    ) async throws -> [Answer] {
        let external = job.tasks.compactMap { task in task.work.map { (task, $0) } }
        guard !external.isEmpty, let cvat, let privkey, let relayClient else { return [] }
        var answers = [Answer]()
        for (task, work) in external {
            let labels = try await cvat.labels(jobId: work.jobId)
            let canonical = try await cvat.canonicalAnnotations(jobId: work.jobId, labels: labels)
            let hash = ExternalWork.hashOf(canonical)

            let commitment = try CvatCommitments.toEvent(
                privkey,
                CvatCommitment(
                    escrowId: job.escrowId, taskKey: task.key,
                    worker: pubkey, annotationsSha256: hash
                ),
                createdAt: Int64(Date().timeIntervalSince1970)
            )
            guard await relayClient.publish(commitment) else {
                throw CvatError.http(0, "could not publish the annotation commitment")
            }
            answers.append(
                Answer(
                    taskKey: task.key,
                    answer: ExternalWork.answer(
                        CvatCompletion(cvatJobId: work.jobId, cvatUserId: 0, annotationsSha256: hash)
                    )
                )
            )
        }
        return answers
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
                let external = try await externalAnswers(job, active)
                let inline = answers.map { Answer(taskKey: $0.key, answer: $0.value) }
                try await session.submit(row, active, external.isEmpty ? inline : external)
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
