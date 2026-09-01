import Foundation

/// Which surface the work expects, and therefore where a client should route it.
public enum WorkSurface: String, Equatable {
    case desktop, mobile, any

    public static func parse(_ raw: String?) -> WorkSurface {
        guard let raw, let parsed = WorkSurface(rawValue: raw.lowercased()) else { return .any }
        return parsed
    }
}

/// Where the work actually lives, when it is not carried in the offer.
///
/// Deliberately thin: `tool` and `url` are all a client needs to route and open
/// it, so a client that has never heard of a tool still shows a working link
/// rather than nothing. Anything a tool needs beyond that lives in `params`.
public struct WorkSource: Equatable {
    public static let resultTags = "tags"

    public let tool: String
    public let url: String
    public let surface: WorkSurface
    public let result: String
    public let params: [String: String]

    public init(
        tool: String,
        url: String,
        surface: WorkSurface = .any,
        result: String = WorkSource.resultTags,
        params: [String: String] = [:]
    ) {
        self.tool = tool
        self.url = url
        self.surface = surface
        self.result = result
        self.params = params
    }
}

/// What a worker asserts instead of an answer: that it finished the work, and
/// what that work hashed to when it read it back.
public struct WorkCompletion: Equatable {
    public let ref: String
    public let resultSha256: String

    public init(ref: String, resultSha256: String) {
        self.ref = ref
        self.resultSha256 = resultSha256
    }
}

/// The phase-0 encoding for work that lives in another tool.
///
/// The mirror of `kotlin/protocol/src/main/kotlin/org/hpb/protocol/ExternalWork.kt`.
/// The two must agree byte for byte on `canonical`, because the worker hashes it
/// on a phone and the recording role re-hashes it on a server; a disagreement
/// there withholds a payout rather than failing a test.
public enum ExternalWork {
    public static let toolCvat = "cvat"

    /// A `text` field is always emitted: clients that predate this encoding fall
    /// back to showing the raw question string when neither `text` nor `image`
    /// is present, which would put JSON in front of a worker.
    public static func question(_ text: String, _ work: WorkSource) -> String {
        var params: [(String, J?)] = []
        for key in work.params.keys.sorted() {
            params.append((key, .str(work.params[key] ?? "")))
        }
        return Cj.write(Cj.obj([
            ("text", .str(text)),
            ("work", Cj.obj([
                ("tool", .str(work.tool)),
                ("url", .str(work.url)),
                ("surface", .str(work.surface.rawValue)),
                ("result", .str(work.result)),
                ("params", Cj.obj(params)),
            ])),
        ]))
    }

    /// Nil for an ordinary inline task. Any tool is accepted, not just CVAT — a
    /// client that cannot work a tool should say so, not pretend the job is not
    /// there.
    public static func workSource(_ question: String) -> WorkSource? {
        guard let parsed = try? Cj.parse(question),
              let work = try? parsed.o("work"),
              let tool = try? work.s("tool"),
              let url = try? work.s("url")
        else { return nil }
        var params: [String: String] = [:]
        if let raw = try? work.o("params"), case .obj(let pairs) = raw {
            for (key, value) in pairs {
                params[key] = value.stringValue
            }
        }
        return WorkSource(
            tool: tool,
            url: url,
            surface: WorkSurface.parse(work.sOrNull("surface")),
            result: work.sOrNull("result") ?? WorkSource.resultTags,
            params: params
        )
    }

    public static func answer(_ completion: WorkCompletion) -> String {
        Cj.write(Cj.obj([
            ("completed", .str("true")),
            ("ref", .str(completion.ref)),
            ("result_sha256", .str(completion.resultSha256)),
        ]))
    }

    public static func completion(_ answer: String) -> WorkCompletion? {
        guard let parsed = try? Cj.parse(answer),
              let ref = try? parsed.s("ref"),
              let hash = try? parsed.s("result_sha256")
        else { return nil }
        return WorkCompletion(ref: ref, resultSha256: hash)
    }

    /// The bytes both sides hash, for the named form. An unrecognised form
    /// returns nil rather than hashing something arbitrary — silently agreeing
    /// on the wrong bytes would withhold a payout later.
    public static func canonical(_ form: String, _ entries: [(Int, String)]) -> String? {
        guard form == WorkSource.resultTags else { return nil }
        return canonicalAnnotations(entries)
    }

    /// Tags: frames ascending, then label, normalized as `Validators.normalize`
    /// does, so a canonical set and a validated answer cannot disagree over
    /// whitespace or case.
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

    /// The hash a worker commits to publicly before submitting.
    public static func hashOf(_ canonical: String) -> String {
        sha256(canonical).hex
    }
}
