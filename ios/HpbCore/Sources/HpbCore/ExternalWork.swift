import Foundation

/// Where the work actually lives, when it is not carried in the offer.
public struct CvatWorkSource: Equatable {
    public let baseUrl: String
    public let org: String
    public let taskId: Int64
    public let jobId: Int64
    public let labels: [String]

    public init(baseUrl: String, org: String, taskId: Int64, jobId: Int64, labels: [String]) {
        self.baseUrl = baseUrl
        self.org = org
        self.taskId = taskId
        self.jobId = jobId
        self.labels = labels
    }

    /// What the worker opens in a browser.
    public var url: String {
        var base = baseUrl
        while base.hasSuffix("/") { base.removeLast() }
        return "\(base)/tasks/\(taskId)/jobs/\(jobId)"
    }
}

/// What a worker asserts instead of an answer: that they finished an assignment
/// in the external tool, and what their work hashed to when they read it back.
public struct CvatCompletion: Equatable {
    public let cvatJobId: Int64
    public let cvatUserId: Int64
    public let annotationsSha256: String

    public init(cvatJobId: Int64, cvatUserId: Int64, annotationsSha256: String) {
        self.cvatJobId = cvatJobId
        self.cvatUserId = cvatUserId
        self.annotationsSha256 = annotationsSha256
    }
}

/// The phase-0 encoding for work that lives in another tool.
///
/// The mirror of `kotlin/protocol/src/main/kotlin/org/hpb/protocol/ExternalWork.kt`.
/// The two must agree byte for byte on `canonicalAnnotations`, because the
/// worker hashes it on a phone and the recording role re-hashes it on a server;
/// a disagreement there withholds a payout rather than failing a test.
public enum ExternalWork {
    public static let toolCvat = "cvat"

    /// A `text` field is always emitted: clients that predate this encoding fall
    /// back to showing the raw question string when neither `text` nor `image`
    /// is present, which would put JSON in front of a worker.
    public static func question(_ text: String, _ work: CvatWorkSource) -> String {
        Cj.write(Cj.obj([
            ("text", .str(text)),
            ("work", Cj.obj([
                ("tool", .str(toolCvat)),
                ("base_url", .str(work.baseUrl)),
                ("org", .str(work.org)),
                ("task_id", Cj.num(work.taskId)),
                ("job_id", Cj.num(work.jobId)),
                ("labels", .arr(work.labels.map { J.str($0) })),
                ("url", .str(work.url)),
            ])),
        ]))
    }

    /// Nil for an ordinary inline task, so old offers keep working.
    public static func workSource(_ question: String) -> CvatWorkSource? {
        guard let parsed = try? Cj.parse(question),
              let work = try? parsed.o("work"),
              (try? work.s("tool")) == toolCvat,
              let baseUrl = try? work.s("base_url"),
              let org = try? work.s("org"),
              let taskId = try? work.l("task_id"),
              let jobId = try? work.l("job_id"),
              let labels = try? work.a("labels")
        else { return nil }
        return CvatWorkSource(
            baseUrl: baseUrl,
            org: org,
            taskId: taskId,
            jobId: jobId,
            labels: labels.compactMap(\.stringValue)
        )
    }

    public static func answer(_ completion: CvatCompletion) -> String {
        Cj.write(Cj.obj([
            ("completed", .str("true")),
            ("cvat_job_id", Cj.num(completion.cvatJobId)),
            ("cvat_user_id", Cj.num(completion.cvatUserId)),
            ("annotations_sha256", .str(completion.annotationsSha256)),
        ]))
    }

    public static func completion(_ answer: String) -> CvatCompletion? {
        guard let parsed = try? Cj.parse(answer),
              let jobId = try? parsed.l("cvat_job_id"),
              let userId = try? parsed.l("cvat_user_id"),
              let hash = try? parsed.s("annotations_sha256")
        else { return nil }
        return CvatCompletion(cvatJobId: jobId, cvatUserId: userId, annotationsSha256: hash)
    }

    /// The bytes both sides hash. Frames ascending, then label; labels
    /// normalized exactly as `Validators.normalize` does, so a canonical
    /// annotation set and a validated answer cannot disagree over whitespace
    /// or case.
    public static func canonicalAnnotations(_ tags: [(Int, String)]) -> String {
        // Written out rather than chained: the fluent form defeats Swift's
        // type-checker on tuple arrays.
        var normalized: [(frame: Int, label: String)] = []
        for tag in tags {
            normalized.append((frame: tag.0, label: Validators.normalize(tag.1)))
        }
        normalized.sort { left, right in
            left.frame == right.frame ? left.label < right.label : left.frame < right.frame
        }
        var lines: [String] = []
        for row in normalized {
            lines.append("\(row.frame):\(row.label)")
        }
        return lines.joined(separator: "\n")
    }

    public static func annotationsHash(_ tags: [(Int, String)]) -> String {
        sha256(canonicalAnnotations(tags)).hex
    }
}
