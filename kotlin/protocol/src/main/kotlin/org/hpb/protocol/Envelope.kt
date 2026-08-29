package org.hpb.protocol

import org.hpb.engine.hexBytes
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.Nip44
import org.hpb.engine.nostr.NostrEvent
import org.hpb.protocol.Pj.s
import org.hpb.protocol.Pj.sOrNull

/**
 * Encrypted point-to-point envelopes (kind 9568): the PSBT co-signing
 * transport. The witness re-runs its own policy checks before signing —
 * an envelope is a request, never authority.
 */
data class SignRequest(val escrowId: String, val payoutId: String, val psbt: String)

data class SignResponse(val escrowId: String, val payoutId: String, val psbt: String?, val error: String? = null)

object Envelopes {
    private fun send(
        privkey: ByteArray,
        recipient: String,
        escrowId: String?,
        payload: String,
        createdAt: Long,
    ): NostrEvent {
        val key = Nip44.conversationKey(privkey, recipient.hexBytes())
        val tags = listOfNotNull(
            listOf("p", recipient),
            escrowId?.let { listOf("x", it) },
        )
        return Events.sign(
            privkey, ProtocolKinds.ENVELOPE, tags,
            Nip44.encrypt(payload, key, nonce()), createdAt,
        )
    }

    fun signRequest(
        privkey: ByteArray,
        witness: String,
        request: SignRequest,
        createdAt: Long,
    ): NostrEvent = send(
        privkey, witness, request.escrowId,
        Pj.obj(
            "v" to Pj.num(ProtocolKinds.VERSION),
            "type" to Pj.str("psbt_sign_request"),
            "payout_id" to Pj.str(request.payoutId),
            "psbt" to Pj.str(request.psbt),
        ).toString(),
        createdAt,
    )

    fun signResponse(
        privkey: ByteArray,
        requester: String,
        response: SignResponse,
        createdAt: Long,
    ): NostrEvent = send(
        privkey, requester, response.escrowId,
        Pj.obj(
            "v" to Pj.num(ProtocolKinds.VERSION),
            "type" to Pj.str("psbt_sign_response"),
            "payout_id" to Pj.str(response.payoutId),
            "psbt" to response.psbt?.let(Pj::str),
            "error" to response.error?.let(Pj::str),
        ).toString(),
        createdAt,
    )

    /** Decrypt an envelope addressed to us; (type, parsed body). */
    fun open(event: NostrEvent, privkey: ByteArray): Pair<String, kotlinx.serialization.json.JsonObject> {
        require(event.kind == ProtocolKinds.ENVELOPE) { "not an envelope" }
        val key = Nip44.conversationKey(privkey, event.pubkey.hexBytes())
        val body = Pj.parse(Nip44.decrypt(event.content, key))
        return body.s("type") to body
    }

    fun parseSignRequest(event: NostrEvent, body: kotlinx.serialization.json.JsonObject): SignRequest =
        SignRequest(
            escrowId = requireNotNull(event.tagValue("x")),
            payoutId = body.s("payout_id"),
            psbt = body.s("psbt"),
        )

    fun parseSignResponse(event: NostrEvent, body: kotlinx.serialization.json.JsonObject): SignResponse =
        SignResponse(
            escrowId = requireNotNull(event.tagValue("x")),
            payoutId = body.s("payout_id"),
            psbt = body.sOrNull("psbt"),
            error = body.sOrNull("error"),
        )

    private fun nonce(): ByteArray =
        ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
}
