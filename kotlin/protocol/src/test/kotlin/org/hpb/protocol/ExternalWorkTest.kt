package org.hpb.protocol

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phase-0 encoding. The canonical annotation form is the load-bearing part:
 * a worker hashes it on a phone and a recording role re-hashes it on a server,
 * so anything order- or case-dependent would break payouts rather than a test.
 */
class ExternalWorkTest {
    private val work = WorkSource(
        tool = "cvat",
        url = "http://cvat.invalid/tasks/12/jobs/42",
        surface = WorkSurface.DESKTOP,
        params = mapOf("org" to "hpb", "task_id" to "12", "job_id" to "42"),
    )

    @Test
    fun `a work source round-trips through the question`() {
        val question = ExternalWork.question("Annotate job 42", work)
        assertEquals(work, ExternalWork.workSource(question))
    }

    /** Clients that predate this encoding show `text`; without it they show raw JSON. */
    @Test
    fun `the question always carries human-readable text`() {
        val question = ExternalWork.question("Annotate job 42", work)
        assertEquals("Annotate job 42", Pj.parse(question).getValue("text").let { it.toString().trim('"') })
    }

    /** A client that has never heard of a tool must still get a usable link. */
    @Test
    fun `an unknown tool still yields a work source`() {
        val exotic = WorkSource(tool = "label-studio", url = "http://ls.invalid/1")
        val parsed = assertNotNull(ExternalWork.workSource(ExternalWork.question("Label it", exotic)))
        assertEquals("label-studio", parsed.tool)
        assertEquals("http://ls.invalid/1", parsed.url)
        assertEquals(WorkSurface.ANY, parsed.surface, "an absent surface should not be desktop")
    }

    @Test
    fun `an unrecognised surface falls back to any`() {
        assertEquals(WorkSurface.ANY, WorkSurface.parse("hologram"))
        assertEquals(WorkSurface.DESKTOP, WorkSurface.parse("DESKTOP"))
    }

    /** Hashing an unknown form would silently withhold a payout later. */
    @Test
    fun `an unsupported result form fails loudly`() {
        assertFailsWith<IllegalStateException> { ExternalWork.canonical("bounding-boxes", emptyList()) }
    }

    @Test
    fun `an inline task is not mistaken for external work`() {
        assertNull(ExternalWork.workSource("""{"text":"pick one","choices":["a","b"]}"""))
        assertNull(ExternalWork.workSource("just a plain question"))
        assertNull(ExternalWork.workSource("""{"work":{"tool":"labelstudio"}}"""), "no url means no work source")
    }

    @Test
    fun `a completion round-trips through the answer`() {
        val completion = WorkCompletion(ref = "42", resultSha256 = "abc123")
        assertEquals(completion, ExternalWork.completion(ExternalWork.answer(completion)))
    }

    @Test
    fun `a plain answer is not mistaken for a completion`() {
        assertNull(ExternalWork.completion("cat"))
        assertNull(ExternalWork.completion("""{"completed":"true"}"""))
        assertNull(ExternalWork.completion("""{"ref":"42"}"""), "a completion without a hash is not one")
    }

    /** Whatever order CVAT hands them back, both sides must hash the same bytes. */
    @Test
    fun `canonical annotations do not depend on order or case`() {
        val one = listOf(2 to "Square", 0 to " circle ", 1 to "TRIANGLE")
        val two = listOf(0 to "circle", 1 to "triangle", 2 to "square")
        assertEquals("0:circle\n1:triangle\n2:square", ExternalWork.canonicalAnnotations(two))
        assertEquals(ExternalWork.annotationsHash(two), ExternalWork.annotationsHash(one))
    }

    /**
     * Pinned, and pinned identically in
     * `ios/HpbCore/Tests/HpbCoreTests/ExternalWorkTests.swift`. A phone hashes
     * this and a server re-hashes it, so drift here withholds a payout rather
     * than failing a test.
     */
    @Test
    fun `the canonical hash is locked across languages`() {
        assertEquals(
            "627028bd8ef551f3c1fd96097bb70f55ff1b386885198b6294454de79f91d89f",
            ExternalWork.annotationsHash(listOf(0 to "circle", 1 to "triangle", 2 to "square")),
        )
    }

    @Test
    fun `different annotations hash differently`() {
        val hash = ExternalWork.annotationsHash(listOf(0 to "circle"))
        assertTrue(hash != ExternalWork.annotationsHash(listOf(0 to "square")))
        assertTrue(hash != ExternalWork.annotationsHash(listOf(1 to "circle")))
    }

    /** A frame may carry more than one tag; the pair, not the frame, is the unit. */
    @Test
    fun `multiple labels on one frame are ordered deterministically`() {
        assertEquals(
            "0:circle\n0:square",
            ExternalWork.canonicalAnnotations(listOf(0 to "square", 0 to "circle")),
        )
    }
}
