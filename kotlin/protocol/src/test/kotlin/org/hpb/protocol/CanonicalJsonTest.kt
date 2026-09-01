package org.hpb.protocol

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the canonical JSON rules written down in `docs/protocol.md`.
 *
 * Serialization is consensus here: event ids are hashes of serialized events and
 * the on-chain manifest commitment is a hash of a serialized offer, so two
 * implementations that disagree by one byte disagree about money. These
 * assertions are the executable half of that spec, and the Swift twin in
 * `ios/HpbCore/Tests/HpbCoreTests/CanonicalJsonTests.swift` pins the same
 * literals.
 */
class CanonicalJsonTest {
    @Test
    fun `no insignificant whitespace`() {
        assertEquals(
            """{"a":1,"b":[1,2]}""",
            Pj.obj("a" to Pj.num(1), "b" to Pj.arr(listOf(Pj.num(1), Pj.num(2)))).toString(),
        )
    }

    /** Order is part of the format: reordering keys changes the hash. */
    @Test
    fun `object keys keep insertion order and are not sorted`() {
        assertEquals(
            """{"b":1,"a":2}""",
            Pj.obj("b" to Pj.num(1), "a" to Pj.num(2)).toString(),
        )
    }

    @Test
    fun `strings escape exactly the documented set`() {
        val raw = "a\"b\\c\b\t\n\u000C\ré\uD83D\uDE00"
        assertEquals(
            "{\"s\":\"a\\\"b\\\\c\\b\\t\\n\\f\\ré\uD83D\uDE00\"}",
            Pj.obj("s" to Pj.str(raw)).toString(),
            "short escapes for the five named controls; text and emoji stay literal",
        )
    }

    /** Other controls become lowercase \u00xx, and only those. */
    @Test
    fun `other control characters use lowercase four digit escapes`() {
        assertEquals(
            "{\"s\":\"\\u0001\\u001f\"}",
            Pj.obj("s" to Pj.str("\u0001\u001F")).toString(),
        )
    }

    @Test
    fun `integers carry no exponent or trailing zero`() {
        assertEquals("""{"n":7}""", Pj.obj("n" to Pj.num(7L)).toString())
        assertEquals("""{"n":0}""", Pj.obj("n" to Pj.num(0)).toString())
    }

    /** The only fractions the protocol carries are plain ones like this. */
    @Test
    fun `doubles keep a trailing point zero when integral`() {
        assertEquals("""{"d":0.5}""", Pj.obj("d" to Pj.num(0.5)).toString())
        assertEquals("""{"d":1.0}""", Pj.obj("d" to Pj.num(1.0)).toString())
    }

    /** Absent is not null: a null-valued field is dropped, never emitted. */
    @Test
    fun `null fields are omitted entirely`() {
        assertEquals(
            """{"a":1}""",
            Pj.obj("a" to Pj.num(1), "b" to null).toString(),
        )
    }
}
