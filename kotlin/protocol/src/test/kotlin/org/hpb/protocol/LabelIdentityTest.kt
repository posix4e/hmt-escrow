package org.hpb.protocol

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Two witnesses must agree on what counts as the same label.
 *
 * Swift's `String` equality is canonical equivalence and Kotlin's is UTF-16
 * code-unit equality, so `café` written NFC and NFD is one label to one and two
 * to the other — and they then compute different winners and different payouts
 * from identical data. The twin of this file is
 * `ios/HpbCore/Tests/HpbCoreTests/LabelIdentityTests.swift`, pinning the same
 * expectations from the other side.
 */
class LabelIdentityTest {
    private val nfc = "caf\u00E9"
    private val nfd = "cafe\u0301"

    @Test
    fun `composed and decomposed forms are different labels`() {
        assertNotEquals(
            Validators.labelKey(nfc),
            Validators.labelKey(nfd),
            "distinct byte sequences must stay distinct labels in both languages",
        )
    }

    @Test
    fun `the key is ascii hex of the normalized utf8 bytes`() {
        assertEquals("636166c3a9", Validators.labelKey(" CAF\u00C9 "))
        assertEquals("636166650301".replace("0301", "cc81"), Validators.labelKey(nfd))
    }

    /** Tallying by key must not merge them, which is what Swift used to do. */
    @Test
    fun `a task split between both forms has no majority`() {
        val rows = listOf(
            Validators.Submitted("t", "worker-a", nfc),
            Validators.Submitted("t", "worker-b", nfd),
        )
        val results = Validators.validate(
            ValidationPolicy(ValidationType.AGREEMENT, agreementThreshold = 0.6),
            rows,
        )
        assertEquals(
            listOf(false, false),
            results.map { it.accepted },
            "one vote each cannot reach a 0.6 quorum; merging them would pay both",
        )
    }
}
