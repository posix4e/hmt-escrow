package org.hpb.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hpb.engine.hex
import org.hpb.engine.sha256
import org.hpb.protocol.Pj.a
import org.hpb.protocol.Pj.b
import org.hpb.protocol.Pj.d
import org.hpb.protocol.Pj.i
import org.hpb.protocol.Pj.l
import org.hpb.protocol.Pj.o
import org.hpb.protocol.Pj.s
import org.hpb.engine.nostr.Events
import org.hpb.engine.nostr.NostrEvent

enum class ValidationType { GROUNDTRUTH, AGREEMENT }

/**
 * Manifest-committed mechanical validation policy. Both strategies are pure
 * functions over the revealed submission set — any observer can recompute
 * validation AND payout correctness.
 */
data class ValidationPolicy(
    val type: ValidationType,
    /** GROUNDTRUTH: sha256("<task_key>:<normalized answer>") allowed set. */
    val groundtruthHashes: Set<String> = emptySet(),
    /** AGREEMENT: assignments per task and the majority fraction required. */
    val assignmentsPerTask: Int = 1,
    val agreementThreshold: Double = 0.5,
)

data class KycPolicy(val required: Boolean, val attesters: List<String> = emptyList())

/** A task list carried INLINE in the offer (Nostr-first artifacts). */
data class Task(val key: String, val question: String)

data class JobOffer(
    val escrowId: String,
    val escrowAddress: String,
    val jobType: String,
    val rewardPerTaskSats: Long,
    val tasks: List<Task>,
    val validation: ValidationPolicy,
    val kyc: KycPolicy,
    val expiresAt: Long,
    val status: String = "open",
) {
    /** The manifest string whose sha256 is committed on-chain at setup. */
    fun manifestJson(): String = Pj.obj(
        "v" to Pj.num(ProtocolKinds.VERSION),
        "job_type" to Pj.str(jobType),
        "reward_per_task_sats" to Pj.num(rewardPerTaskSats),
        "tasks" to Pj.arr(
            tasks.map { Pj.obj("key" to Pj.str(it.key), "question" to Pj.str(it.question)) },
        ),
        "validation" to validationJson(),
    ).toString()

    fun manifestHash(): ByteArray = sha256(manifestJson().toByteArray())

    private fun validationJson() = Pj.obj(
        "type" to Pj.str(validation.type.name.lowercase()),
        "groundtruth_hashes" to Pj.arr(validation.groundtruthHashes.sorted().map(Pj::str)),
        "assignments_per_task" to Pj.num(validation.assignmentsPerTask),
        "agreement_threshold" to Pj.num(validation.agreementThreshold),
    )
}

object Offers {
    fun toEvent(privkey: ByteArray, offer: JobOffer, createdAt: Long): NostrEvent =
        Events.sign(
            privkey, ProtocolKinds.JOB_OFFER,
            tags = listOf(
                listOf("d", offer.escrowId),
                listOf("x", offer.escrowId),
                listOf("t", offer.jobType),
            ),
            content = Pj.obj(
                "v" to Pj.num(ProtocolKinds.VERSION),
                "escrow_address" to Pj.str(offer.escrowAddress),
                "reward_per_task_sats" to Pj.num(offer.rewardPerTaskSats),
                "manifest" to Pj.str(offer.manifestJson()),
                "kyc" to Pj.obj(
                    "required" to Pj.bool(offer.kyc.required),
                    "attesters" to Pj.arr(offer.kyc.attesters.map(Pj::str)),
                ),
                "expires_at" to Pj.num(offer.expiresAt),
                "status" to Pj.str(offer.status),
            ).toString(),
            createdAt = createdAt,
        )

    fun fromEvent(event: NostrEvent): JobOffer {
        require(event.kind == ProtocolKinds.JOB_OFFER) { "not a job offer" }
        val content = Pj.parse(event.content)
        val manifest = Pj.parse(content.s("manifest"))
        val validation = manifest.o("validation")
        val kyc = content.o("kyc")
        return JobOffer(
            escrowId = requireNotNull(event.tagValue("d")) { "offer missing d tag" },
            escrowAddress = content.s("escrow_address"),
            jobType = manifest.s("job_type"),
            rewardPerTaskSats = manifest.l("reward_per_task_sats"),
            tasks = manifest.a("tasks").map {
                Task(it.jsonObject.s("key"), it.jsonObject.s("question"))
            },
            validation = ValidationPolicy(
                type = ValidationType.valueOf(validation.s("type").uppercase()),
                groundtruthHashes = validation.a("groundtruth_hashes")
                    .map { it.jsonPrimitive.content }.toSet(),
                assignmentsPerTask = validation.i("assignments_per_task"),
                agreementThreshold = validation.d("agreement_threshold"),
            ),
            kyc = KycPolicy(
                required = kyc.b("required"),
                attesters = kyc.a("attesters").map { it.jsonPrimitive.content },
            ),
            expiresAt = content.l("expires_at"),
            status = content.s("status"),
        )
    }

    /** The committed manifest hash hex (what SETUP carries on-chain). */
    fun manifestHashHex(offer: JobOffer): String = offer.manifestHash().hex()
}
