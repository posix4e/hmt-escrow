package org.hpb.protocol

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phase-0 encoding. The canonical annotation form is the load-bearing part:
 * a worker hashes it on a phone and a recording role re-hashes it on a server,
 * so anything order- or case-dependent would break payouts rather than a test.
 */
class ExternalWorkTest {
    private val work = CvatWorkSource(
        baseUrl = "http://cvat.invalid",
        org = "hpb",
        taskId = 12,
        jobId = 42,
        labels = listOf("circle", "square", "triangle"),
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

    @Test
    fun `the browser url points at the cvat job`() {
        assertEquals("http://cvat.invalid/tasks/12/jobs/42", work.url)
    }

    @Test
    fun `an inline task is not mistaken for external work`() {
        assertNull(ExternalWork.workSource("""{"text":"pick one","choices":["a","b"]}"""))
        assertNull(ExternalWork.workSource("just a plain question"))
        assertNull(ExternalWork.workSource("""{"work":{"tool":"labelstudio","base_url":"x"}}"""))
    }

    @Test
    fun `a completion round-trips through the answer`() {
        val completion = CvatCompletion(cvatJobId = 42, cvatUserId = 7, annotationsSha256 = "abc123")
        assertEquals(completion, ExternalWork.completion(ExternalWork.answer(completion)))
    }

    @Test
    fun `a plain answer is not mistaken for a completion`() {
        assertNull(ExternalWork.completion("cat"))
        assertNull(ExternalWork.completion("""{"completed":"true"}"""))
    }

    /** Whatever order CVAT hands them back, both sides must hash the same bytes. */
    @Test
    fun `canonical annotations do not depend on order or case`() {
        val one = listOf(2 to "Square", 0 to " circle ", 1 to "TRIANGLE")
        val two = listOf(0 to "circle", 1 to "triangle", 2 to "square")
        assertEquals("0:circle\n1:triangle\n2:square", ExternalWork.canonicalAnnotations(two))
        assertEquals(ExternalWork.annotationsHash(two), ExternalWork.annotationsHash(one))
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
