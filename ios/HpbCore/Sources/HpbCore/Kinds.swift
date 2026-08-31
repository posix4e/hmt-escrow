/// The protocol's Nostr kind registry — must match the Kotlin reference
/// (kotlin/protocol/ProtocolKinds.kt) exactly.
public enum ProtocolKinds {
    // Addressable
    public static let jobOffer = 33400
    public static let attestation = 33405

    // Regular
    public static let claim = 9560
    public static let grant = 9561
    public static let resign = 9562
    public static let submission = 9563
    public static let validation = 9564
    public static let receipt = 9565
    public static let cvatAccessRequest = 9570
    public static let cvatAccessGrant = 9571
    public static let cvatCommitment = 9572

    public static let version = 1
}
