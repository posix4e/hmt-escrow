package org.hpb.engine.nostr

/** Nostr event kinds and namespaces used by the protocol. */
object Kinds {
    /** NIP-78 application-specific addressable events: the KVStore. */
    const val KV = 30078

    /** Escrow coordination records (regular; the audit trail). */
    const val RECORD = 9559

    const val KV_NAMESPACE = "org.humanprotocol.kv"
}

/**
 * Operator metadata as NIP-78 addressable events: latest-wins per
 * (pubkey, kind, d-tag) is native relay behavior. Values are operator-signed;
 * reads verify signatures and take the newest event across relays.
 */
class KvStore(
    private val client: NostrClient,
    private val namespace: String = Kinds.KV_NAMESPACE,
) {
    fun set(privkey: ByteArray, key: String, value: String): Boolean =
        client.publish(
            Events.sign(
                privkey, Kinds.KV,
                tags = listOf(listOf("d", "$namespace:$key"), listOf("k", key)),
                content = value,
            ),
        )

    fun get(pubkey: String, key: String): String? =
        client.fetch(
            NostrFilter(kinds = listOf(Kinds.KV), authors = listOf(pubkey), dTag = "$namespace:$key"),
        )
            .filter { it.pubkey == pubkey }
            .maxByOrNull { it.createdAt }
            ?.content

    /** Co-signer fee discovery (used by escrow setup when fees aren't given). */
    fun feePct(pubkey: String): Int? = get(pubkey, "fee")?.toIntOrNull()
}
