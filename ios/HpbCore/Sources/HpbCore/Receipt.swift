import Foundation

public struct PayoutLine: Equatable {
    public let worker: String
    public let address: String
    public let sats: Int64

    public init(worker: String, address: String, sats: Int64) {
        self.worker = worker
        self.address = address
        self.sats = sats
    }
}

/// The payout receipt: auditable against the on-chain PAYOUT transaction —
/// wallets verify the txid via their own chain view.
public struct Receipt {
    public let escrowId: String
    public let payoutId: String
    public let txid: String
    public let lines: [PayoutLine]
    public let final: Bool

    public init(escrowId: String, payoutId: String, txid: String, lines: [PayoutLine], final: Bool) {
        self.escrowId = escrowId
        self.payoutId = payoutId
        self.txid = txid
        self.lines = lines
        self.final = final
    }
}

public enum Receipts {
    public static func fromEvent(_ event: NostrEvent) throws -> Receipt {
        guard event.kind == ProtocolKinds.receipt else { throw HpbError.invalid("not a receipt") }
        let content = try Cj.parse(event.content)
        guard let escrowId = event.tagValue("x") else { throw HpbError.invalid("receipt missing x tag") }
        return Receipt(
            escrowId: escrowId,
            payoutId: try content.s("payout_id"),
            txid: try content.s("txid"),
            lines: try content.a("lines").map {
                PayoutLine(worker: try $0.s("worker"), address: try $0.s("address"), sats: try $0.l("sats"))
            },
            final: try content.b("final")
        )
    }
}
