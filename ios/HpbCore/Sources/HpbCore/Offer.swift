import Foundation

public enum ValidationType: String {
    case groundtruth
    case agreement
}

/// Manifest-committed mechanical validation policy — pure functions over
/// the revealed submission set.
public struct ValidationPolicy {
    public let type: ValidationType
    public let groundtruthHashes: Set<String>
    public let assignmentsPerTask: Int
    public let agreementThreshold: Double

    public init(
        type: ValidationType,
        groundtruthHashes: Set<String> = [],
        assignmentsPerTask: Int = 1,
        agreementThreshold: Double = 0.5
    ) {
        self.type = type
        self.groundtruthHashes = groundtruthHashes
        self.assignmentsPerTask = assignmentsPerTask
        self.agreementThreshold = agreementThreshold
    }
}

public struct KycPolicy {
    public let required: Bool
    public let attesters: [String]

    public init(required: Bool, attesters: [String] = []) {
        self.required = required
        self.attesters = attesters
    }
}

/// A task list carried INLINE in the offer (Nostr-first artifacts).
public struct TaskItem: Equatable {
    public let key: String
    public let question: String

    public init(key: String, question: String) {
        self.key = key
        self.question = question
    }
}

public struct JobOffer {
    public let escrowId: String
    public let escrowAddress: String
    public let jobType: String
    public let rewardPerTaskSats: Int64
    public let tasks: [TaskItem]
    public let validation: ValidationPolicy
    public let kyc: KycPolicy
    public let expiresAt: Int64
    public let status: String

    public init(
        escrowId: String, escrowAddress: String, jobType: String,
        rewardPerTaskSats: Int64, tasks: [TaskItem], validation: ValidationPolicy,
        kyc: KycPolicy, expiresAt: Int64, status: String = "open"
    ) {
        self.escrowId = escrowId
        self.escrowAddress = escrowAddress
        self.jobType = jobType
        self.rewardPerTaskSats = rewardPerTaskSats
        self.tasks = tasks
        self.validation = validation
        self.kyc = kyc
        self.expiresAt = expiresAt
        self.status = status
    }

    /// The manifest string whose sha256 is committed on-chain at setup.
    public func manifestJson() -> String {
        Cj.write(Cj.obj([
            ("v", Cj.num(ProtocolKinds.version)),
            ("job_type", .str(jobType)),
            ("reward_per_task_sats", Cj.num(rewardPerTaskSats)),
            ("tasks", .arr(tasks.map {
                Cj.obj([("key", .str($0.key)), ("question", .str($0.question))])
            })),
            ("validation", validationJson()),
        ]))
    }

    public func manifestHash() -> [UInt8] {
        sha256(manifestJson())
    }

    private func validationJson() -> J {
        Cj.obj([
            ("type", .str(validation.type.rawValue)),
            ("groundtruth_hashes", .arr(
                validation.groundtruthHashes.sorted(by: utf16Less).map(J.str)
            )),
            ("assignments_per_task", Cj.num(validation.assignmentsPerTask)),
            ("agreement_threshold", Cj.num(validation.agreementThreshold)),
        ])
    }
}

public enum Offers {
    public static func toEvent(_ privkey: [UInt8], _ offer: JobOffer, createdAt: Int64) throws -> NostrEvent {
        try Events.sign(
            privkey, kind: ProtocolKinds.jobOffer,
            tags: [
                ["d", offer.escrowId],
                ["x", offer.escrowId],
                ["t", offer.jobType],
            ],
            content: Cj.write(Cj.obj([
                ("v", Cj.num(ProtocolKinds.version)),
                ("escrow_address", .str(offer.escrowAddress)),
                ("reward_per_task_sats", Cj.num(offer.rewardPerTaskSats)),
                ("manifest", .str(offer.manifestJson())),
                ("kyc", Cj.obj([
                    ("required", .bool(offer.kyc.required)),
                    ("attesters", .arr(offer.kyc.attesters.map(J.str))),
                ])),
                ("expires_at", Cj.num(offer.expiresAt)),
                ("status", .str(offer.status)),
            ])),
            createdAt: createdAt
        )
    }

    public static func fromEvent(_ event: NostrEvent) throws -> JobOffer {
        guard event.kind == ProtocolKinds.jobOffer else { throw HpbError.invalid("not a job offer") }
        let content = try Cj.parse(event.content)
        let manifest = try Cj.parse(content.s("manifest"))
        let validation = try manifest.o("validation")
        let kyc = try content.o("kyc")
        guard let escrowId = event.tagValue("d") else { throw HpbError.invalid("offer missing d tag") }
        guard let type = ValidationType(rawValue: try validation.s("type").lowercased()) else {
            throw HpbError.invalid("unknown validation type")
        }
        return JobOffer(
            escrowId: escrowId,
            escrowAddress: try content.s("escrow_address"),
            jobType: try manifest.s("job_type"),
            rewardPerTaskSats: try manifest.l("reward_per_task_sats"),
            tasks: try manifest.a("tasks").map {
                TaskItem(key: try $0.s("key"), question: try $0.s("question"))
            },
            validation: ValidationPolicy(
                type: type,
                groundtruthHashes: Set(try validation.a("groundtruth_hashes").compactMap(\.stringValue)),
                assignmentsPerTask: try validation.i("assignments_per_task"),
                agreementThreshold: try validation.d("agreement_threshold")
            ),
            kyc: KycPolicy(
                required: try kyc.b("required"),
                attesters: try kyc.a("attesters").compactMap(\.stringValue)
            ),
            expiresAt: try content.l("expires_at"),
            status: try content.s("status")
        )
    }
}
