package org.hpb.protocol

/**
 * The protocol's Nostr kind registry. Addressable kinds hold "current state
 * by a single author" (relay-native latest-wins); every lifecycle fact is a
 * regular, immutable kind — the audit trail.
 */
object ProtocolKinds {
    // Addressable
    const val JOB_OFFER = 33400
    const val REGISTRATION = 33401 // reserved: hosted-oracle deployments
    const val REGISTRATION_ACK = 33402 // reserved
    const val ATTESTATION = 33405
    const val REPUTATION_SNAPSHOT = 33406

    // Regular
    const val CLAIM = 9560
    const val GRANT = 9561
    const val RESIGN = 9562
    const val SUBMISSION = 9563
    const val VALIDATION = 9564
    const val RECEIPT = 9565
    const val ASSESSMENT = 9566
    const val ABUSE_REPORT = 9567
    const val ENVELOPE = 9568
    const val RECORD = org.hpb.engine.nostr.Kinds.RECORD
    const val PROOF = 9569 // reserved: ZK validation/payout proofs (roadmap)

    const val VERSION = 1
}
