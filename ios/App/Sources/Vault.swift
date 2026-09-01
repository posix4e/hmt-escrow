import Foundation
import LocalAuthentication
import Security

/// Everything secret this worker holds.
///
/// One store, one service name, and one set of rules — replacing two ad-hoc
/// Keychain call sites that used different services and set no accessibility
/// class at all. What it holds:
///
/// - the **worker key**, which *is* the worker's identity: losing it orphans
///   every claim and any unpaid earnings, which is exactly what happened when a
///   reinstall wiped it
/// - **per-tool credentials**, keyed by tool rather than hardcoded to CVAT
/// - **delegated tokens** issued to agents, so they can be listed and revoked
enum Vault {
    enum Item {
        case workerKey
        case toolCredential(tool: String)
        case delegated(tool: String, agent: String)

        var account: String {
            switch self {
            case .workerKey: return "worker.key"
            case .toolCredential(let tool): return "tool.\(tool).credential"
            case .delegated(let tool, let agent): return "delegated.\(tool).\(agent)"
            }
        }
    }

    /// Whether secrets ride iCloud Keychain to this worker's other devices.
    ///
    /// Off by default, and a real tradeoff rather than a convenience: a key that
    /// controls bitcoin then inherits the security of an Apple account. It also
    /// changes the accessibility class, because synchronizable items cannot be
    /// `ThisDeviceOnly` — so this is a genuine branch, not a flag.
    static var syncEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: syncKey) }
        set { UserDefaults.standard.set(newValue, forKey: syncKey) }
    }

    static func read(_ item: Item) -> String? {
        var query = base(item)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    static func write(_ item: Item, _ value: String) -> Bool {
        delete(item)
        var query = base(item)
        query[kSecValueData as String] = Data(value.utf8)
        // AfterFirstUnlock, not WhenUnlocked: the app polls every couple of
        // seconds and may be running with the device locked. WhenUnlocked would
        // fail those reads — the locked-Keychain failure mode that bites wallets.
        query[kSecAttrAccessible as String] = syncEnabled
            ? kSecAttrAccessibleAfterFirstUnlock
            : kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        // A lookup may use `Any`, but an add must state which it is.
        query[kSecAttrSynchronizable as String] = syncEnabled
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    static func delete(_ item: Item) {
        SecItemDelete(base(item) as CFDictionary)
    }

    /// Every delegated token this worker has issued, so they can be revoked.
    static func delegatedAccounts() -> [String] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        query[kSecAttrSynchronizable as String] = kSecAttrSynchronizableAny
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let rows = result as? [[String: Any]]
        else { return [] }
        return rows.compactMap { $0[kSecAttrAccount as String] as? String }
            .filter { $0.hasPrefix("delegated.") }
    }

    /// Reveal a secret for backup or handoff, behind the device owner's presence.
    ///
    /// Only export and agent handoff are gated. Routine reads stay ungated on
    /// purpose: prompting on every poll would break background operation and
    /// train the owner to approve without reading.
    static func reveal(_ item: Item, reason: String) async throws -> String {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            throw VaultError.unavailable(error?.localizedDescription ?? "no authentication available")
        }
        let ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        guard ok else { throw VaultError.denied }
        guard let value = read(item) else { throw VaultError.missing }
        return value
    }

    private static func base(_ item: Item) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: item.account,
        ]
        // Any, so a lookup finds the item whichever side of a sync toggle it
        // was written on; writes pick a definite value below.
        query[kSecAttrSynchronizable as String] = kSecAttrSynchronizableAny
        return query
    }

    private static let service = "org.hpb.worker"
    private static let syncKey = "vaultSyncEnabled"
}

enum VaultError: Error {
    case denied
    case missing
    case unavailable(String)
}
