package org.hpb.protocol

import org.hpb.engine.hexBytes
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.Nip44
import org.hpb.engine.nostr.NostrEvent
import org.hpb.protocol.Pj.s

/** What a worker tells the launcher: the CVAT identity it wants admitted. */
data class CvatAccessRequest(val claimEventId: String, val escrowId: String, val cvatEmail: String)

/** What the launcher tells the worker back. [invitationKey] is the secret half. */
data class CvatAccessGrant(
    val grantEventId: String,
    val escrowId: String,
    val workerPubkey: String,
    val cvatId: Long,
    val baseUrl: String,
    val orgSlug: String,
    val invitationKey: String,
)

/**
 * The public half of an access grant: which Nostr worker is which CVAT user.
 *
 * Readable without any key, because every witness has to re-check the identity
 * guard for itself — a launcher that admitted one CVAT account under two worker
 * pubkeys would let one person agree with themselves.
 */
data class CvatBinding(
    val grantEventId: String,
    val escrowId: String,
    val workerPubkey: String,
    val cvatId: Long,
    val createdAt: Long,
    val eventId: String,
)

/**
 * The CVAT access handshake, carried *beside* the claim and the grant rather
 * than inside them: claim and grant contents are closed shapes in the
 * byte-locked vector corpus, so adding fields there would force the corpus and
 * the Swift core to move in lockstep for a mechanism still finding its shape.
 * These are separate events keyed to those event ids.
 *
 * The invitation key is NIP-44 encrypted to the worker. The CVAT user id rides
 * as a public tag — NIP-44 covers `content`, never `tags` — because it is not a
 * secret and every witness needs it.
 */
object CvatAccessCodec {
    fun request(
        privkey: ByteArray,
        launcherPubkey: String,
        r: CvatAccessRequest,
        createdAt: Long,
        nonce: ByteArray = randomNonce(),
    ): NostrEvent {
        val plaintext = Pj.obj(
            "v" to Pj.num(ProtocolKinds.VERSION),
            "cvat_email" to Pj.str(r.cvatEmail),
        ).toString()
        val key = Nip44.conversationKey(privkey, launcherPubkey.hexBytes())
        return Events.sign(
            privkey, ProtocolKinds.CVAT_ACCESS_REQUEST,
            tags = listOf(
                listOf("e", r.claimEventId),
                listOf("x", r.escrowId),
                listOf("p", launcherPubkey),
            ),
            content = Nip44.encrypt(plaintext, key, nonce),
            createdAt = createdAt,
        )
    }

    fun parseRequest(event: NostrEvent, launcherPrivkey: ByteArray): CvatAccessRequest {
        require(event.kind == ProtocolKinds.CVAT_ACCESS_REQUEST) { "not a cvat access request" }
        val key = Nip44.conversationKey(launcherPrivkey, event.pubkey.hexBytes())
        val content = Pj.parse(Nip44.decrypt(event.content, key))
        return CvatAccessRequest(
            claimEventId = requireNotNull(event.tagValue("e")) { "access request names no claim" },
            escrowId = requireNotNull(event.tagValue("x")) { "access request names no escrow" },
            cvatEmail = content.s("cvat_email"),
        )
    }

    fun grant(
        privkey: ByteArray,
        g: CvatAccessGrant,
        createdAt: Long,
        nonce: ByteArray = randomNonce(),
    ): NostrEvent {
        val plaintext = Pj.obj(
            "v" to Pj.num(ProtocolKinds.VERSION),
            "invitation_key" to Pj.str(g.invitationKey),
            "base_url" to Pj.str(g.baseUrl),
            "org" to Pj.str(g.orgSlug),
        ).toString()
        val key = Nip44.conversationKey(privkey, g.workerPubkey.hexBytes())
        return Events.sign(
            privkey, ProtocolKinds.CVAT_ACCESS_GRANT,
            tags = listOf(
                listOf("e", g.grantEventId),
                listOf("x", g.escrowId),
                listOf("p", g.workerPubkey),
                listOf("cvat_id", g.cvatId.toString()),
            ),
            content = Nip44.encrypt(plaintext, key, nonce),
            createdAt = createdAt,
        )
    }

    /** The worker's view: decrypts the invitation key it needs in order to accept. */
    fun parseGrant(event: NostrEvent, workerPrivkey: ByteArray): CvatAccessGrant {
        val binding = requireNotNull(binding(event)) { "not a cvat access grant" }
        val key = Nip44.conversationKey(workerPrivkey, event.pubkey.hexBytes())
        val content = Pj.parse(Nip44.decrypt(event.content, key))
        return CvatAccessGrant(
            grantEventId = binding.grantEventId,
            escrowId = binding.escrowId,
            workerPubkey = binding.workerPubkey,
            cvatId = binding.cvatId,
            baseUrl = content.s("base_url"),
            orgSlug = content.s("org"),
            invitationKey = content.s("invitation_key"),
        )
    }

    /** Any observer's view: the binding alone, no key required. */
    fun binding(event: NostrEvent): CvatBinding? {
        if (event.kind != ProtocolKinds.CVAT_ACCESS_GRANT) return null
        val cvatId = event.tagValue("cvat_id")?.toLongOrNull() ?: return null
        return CvatBinding(
            grantEventId = event.tagValue("e") ?: return null,
            escrowId = event.tagValue("x") ?: return null,
            workerPubkey = event.tagValue("p") ?: return null,
            cvatId = cvatId,
            createdAt = event.createdAt,
            eventId = event.id,
        )
    }

    private fun randomNonce(): ByteArray =
        ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }

    private const val NONCE_BYTES = 32
}

/**
 * The identity guard: within one escrow, a CVAT account backs exactly one
 * worker and a worker holds exactly one CVAT account.
 *
 * Without it, one person registers two Nostr keys against a single CVAT
 * account, takes two assignments on a task and agrees with themselves —
 * defeating inter-worker agreement and cross-checked recording alike.
 *
 * Applied by the launcher when admitting, and re-derived independently by every
 * witness, so it must be mechanical: ties resolve by earliest [CvatBinding]
 * first, then by event id, the same deterministic order the reducer uses.
 */
object CvatIdentity {
    data class Resolution(val admitted: List<CvatBinding>, val rejected: List<CvatBinding>)

    fun resolve(bindings: List<CvatBinding>): Resolution {
        val admitted = mutableListOf<CvatBinding>()
        val rejected = mutableListOf<CvatBinding>()
        val byCvatId = mutableSetOf<Long>()
        val byWorker = mutableSetOf<String>()
        bindings.sortedWith(compareBy({ it.createdAt }, { it.eventId })).forEach { binding ->
            // Claim the identifiers only on admission: a binding rejected for a
            // duplicate worker must not also burn a CVAT account nobody holds.
            if (binding.cvatId in byCvatId || binding.workerPubkey in byWorker) {
                rejected += binding
            } else {
                byCvatId += binding.cvatId
                byWorker += binding.workerPubkey
                admitted += binding
            }
        }
        return Resolution(admitted, rejected)
    }

    /** The worker → CVAT account map an escrow's observers agree on. */
    fun admitted(bindings: List<CvatBinding>): Map<String, Long> =
        resolve(bindings).admitted.associate { it.workerPubkey to it.cvatId }
}
