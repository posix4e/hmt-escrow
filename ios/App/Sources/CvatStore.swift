import Foundation
import HpbCore
import SwiftUI

/// The worker's own CVAT session.
///
/// The token lives in the Keychain on this device and is never published,
/// never sent to the launcher, and never put in a Nostr event. That is the
/// property the whole design rests on: the launcher can assign a job and read
/// the result, but it cannot annotate as you, because it does not have this.
@MainActor
final class CvatStore: ObservableObject {
    @AppStorage("cvatBaseUrl") var baseUrl = ""
    @AppStorage("cvatUsername") var username = ""
    @AppStorage("cvatEmail") var email = ""

    @Published var signedIn = false
    @Published var status = ""

    private var token: String? {
        didSet { signedIn = token != nil }
    }

    init() {
        token = Keychain.read(Self.tokenKey)
        signedIn = token != nil
    }

    func signIn(password: String) async {
        guard !baseUrl.isEmpty, !username.isEmpty else {
            status = "Set the CVAT address and username first"
            return
        }
        do {
            let body = Cj.write(Cj.obj([
                ("username", .str(username)),
                ("password", .str(password)),
            ]))
            let data = try await send("POST", "/api/auth/login", body: body, token: nil)
            let key = try Cj.parse(String(decoding: data, as: UTF8.self)).s("key")
            Keychain.write(Self.tokenKey, key)
            token = key
            status = "Signed in to CVAT as \(username)"
        } catch {
            status = "CVAT sign-in failed: \(error)"
        }
    }

    func signOut() {
        Keychain.delete(Self.tokenKey)
        token = nil
        status = "Signed out"
    }

    /// Join the organization the launcher invited this account to.
    ///
    /// Idempotent: when the address already belonged to a registered account
    /// CVAT accepts the invitation as it creates it, so a second accept is a
    /// 400 rather than a failure.
    func accept(invitationKey: String) async throws {
        do {
            _ = try await send("POST", "/api/invitations/\(invitationKey)/accept", body: "{}", token: token)
        } catch CvatError.http(let code, let body) where code == 400 && body.contains("already accepted") {
            return
        }
    }

    /// Read back what *you* drew, so the app can commit to it before submitting.
    func canonicalAnnotations(jobId: Int64, labels: [Int64: String]) async throws -> String {
        let data = try await send("GET", "/api/jobs/\(jobId)/annotations/", body: nil, token: token)
        let parsed = try Cj.parse(String(decoding: data, as: UTF8.self))
        var tags: [(Int, String)] = []
        for tag in try parsed.a("tags") {
            let frame = try tag.i("frame")
            let labelId = try tag.l("label_id")
            tags.append((frame, labels[labelId] ?? "unknown"))
        }
        return ExternalWork.canonicalAnnotations(tags)
    }

    func labels(taskId: Int64) async throws -> [Int64: String] {
        let data = try await send("GET", "/api/labels?task_id=\(taskId)&page_size=100", body: nil, token: token)
        let parsed = try Cj.parse(String(decoding: data, as: UTF8.self))
        var byId: [Int64: String] = [:]
        for row in try parsed.a("results") {
            byId[try row.l("id")] = try row.s("name")
        }
        return byId
    }

    private func send(_ method: String, _ path: String, body: String?, token: String?) async throws -> Data {
        guard let url = URL(string: baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else {
            throw CvatError.badUrl
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        token.map { request.setValue("Token \($0)", forHTTPHeaderField: "Authorization") }
        body.map { request.httpBody = Data($0.utf8) }
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw CvatError.http(code, String(decoding: data, as: UTF8.self))
        }
        return data
    }

    private static let tokenKey = "cvat.token"
}

enum CvatError: Error {
    case badUrl
    case http(Int, String)
}

/// Small Keychain wrapper — the CVAT token is a credential, not a preference,
/// so it does not belong in UserDefaults next to the relay list.
enum Keychain {
    static func read(_ account: String) -> String? {
        var query = base(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func write(_ account: String, _ value: String) {
        delete(account)
        var query = base(account)
        query[kSecValueData as String] = Data(value.utf8)
        SecItemAdd(query as CFDictionary, nil)
    }

    static func delete(_ account: String) {
        SecItemDelete(base(account) as CFDictionary)
    }

    private static func base(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.hpb.app",
            kSecAttrAccount as String: account,
        ]
    }
}
