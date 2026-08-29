package org.hpb.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.hexBytes
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.Nip44
import org.hpb.engine.nostr.NostrEvent
import org.hpb.protocol.Pj.a
import org.hpb.protocol.Pj.l
import org.hpb.protocol.Pj.s
import org.hpb.protocol.Pj.sOrNull

data class Claim(
    val offerEventId: String,
    val escrowId: String,
    val payoutAddress: String,
    val attestationEventIds: List<String>,
)

data class Grant(
    val claimEventId: String,
    val escrowId: String,
    val granted: Boolean,
    val taskKeys: List<String>,
    val expiresAt: Long,
    val reason: String? = null,
)

data class Answer(val taskKey: String, val answer: String)

data class Submission(val grantEventId: String, val escrowId: String, val answers: List<Answer>)

/** Claim/grant/resign/submission codecs. The grant is the countersignature
 *  that resolves claim races: only the offer author's grants allocate work. */
object Assignments {
    fun claim(privkey: ByteArray, launcher: String, c: Claim, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.CLAIM,
            tags = listOf(
                listOf("e", c.offerEventId),
                listOf("x", c.escrowId),
                listOf("p", launcher),
            ),
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "payout_address" to Pj.str(c.payoutAddress),
                "attestations" to Pj.arr(c.attestationEventIds.map(Pj::str)),
            ).toString(),
            createdAt = createdAt,
        )

    fun parseClaim(event: NostrEvent): Claim {
        require(event.kind == ProtocolKinds.CLAIM) { "not a claim" }
        val content = Pj.parse(event.content)
        return Claim(
            offerEventId = requireNotNull(event.tagValue("e")),
            escrowId = requireNotNull(event.tagValue("x")),
            payoutAddress = content.s("payout_address"),
            attestationEventIds = content.a("attestations").map { it.jsonPrimitive.content },
        )
    }

    fun grant(privkey: ByteArray, worker: String, g: Grant, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.GRANT,
            tags = listOf(
                listOf("e", g.claimEventId),
                listOf("x", g.escrowId),
                listOf("p", worker),
            ),
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "status" to Pj.str(if (g.granted) "granted" else "rejected"),
                "task_keys" to Pj.arr(g.taskKeys.map(Pj::str)),
                "expires_at" to Pj.num(g.expiresAt),
                "reason" to g.reason?.let(Pj::str),
            ).toString(),
            createdAt = createdAt,
        )

    fun parseGrant(event: NostrEvent): Grant {
        require(event.kind == ProtocolKinds.GRANT) { "not a grant" }
        val content = Pj.parse(event.content)
        return Grant(
            claimEventId = requireNotNull(event.tagValue("e")),
            escrowId = requireNotNull(event.tagValue("x")),
            granted = content.s("status") == "granted",
            taskKeys = content.a("task_keys").map { it.jsonPrimitive.content },
            expiresAt = content.l("expires_at"),
            reason = content.sOrNull("reason"),
        )
    }

    fun resign(
        privkey: ByteArray,
        launcher: String,
        grantEventId: String,
        escrowId: String,
        createdAt: Long,
    ): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.RESIGN,
            tags = listOf(
                listOf("e", grantEventId),
                listOf("x", escrowId),
                listOf("p", launcher),
            ),
            content = Pj.obj("v" to Pj.num(ProtocolKinds.VERSION)).toString(),
            createdAt = createdAt,
        )

    /** Answers are NIP-44 encrypted to the validator (copy-protection AND
     *  commitment). The nonce is random in production; fixed-nonce override
     *  exists ONLY so the vector corpus is byte-stable. */
    fun submission(
        privkey: ByteArray,
        validatorPubkey: String,
        s: Submission,
        createdAt: Long,
        nonce: ByteArray = randomNonce(),
    ): NostrEvent {
        val plaintext = Pj.obj(
            "v" to Pj.num(ProtocolKinds.VERSION),
            "answers" to Pj.arr(
                s.answers.map {
                    Pj.obj("task_key" to Pj.str(it.taskKey), "answer" to Pj.str(it.answer))
                },
            ),
        ).toString()
        val key = Nip44.conversationKey(privkey, validatorPubkey.hexBytes())
        return Events.sign(
            privkey, ProtocolKinds.SUBMISSION,
            tags = listOf(
                listOf("e", s.grantEventId),
                listOf("x", s.escrowId),
                listOf("p", validatorPubkey),
            ),
            content = Nip44.encrypt(plaintext, key, nonce),
            createdAt = createdAt,
        )
    }

    /** The validator decrypts with its key + the worker's pubkey. */
    fun decryptSubmission(event: NostrEvent, validatorPrivkey: ByteArray): List<Answer> {
        val key = Nip44.conversationKey(validatorPrivkey, event.pubkey.hexBytes())
        val content = Pj.parse(Nip44.decrypt(event.content, key))
        return content.a("answers").map {
            Answer(it.jsonObject.s("task_key"), it.jsonObject.s("answer"))
        }
    }

    private fun randomNonce(): ByteArray =
        ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
}
