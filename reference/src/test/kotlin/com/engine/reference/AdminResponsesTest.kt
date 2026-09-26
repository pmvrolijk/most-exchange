package com.engine.reference

import io.aeron.cluster.codecs.AdminRequestType
import io.aeron.cluster.codecs.AdminResponseCode
import org.agrona.concurrent.UnsafeBuffer
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design.md §1: a snapshot requester reports the cluster's answer, not the offer -- OK once taken,
 * the denial and its message otherwise, a timeout as a timeout. Before this, a refused request was
 * reported as confirmed because only the offer was checked.
 */
class AdminResponsesTest {

    private val responses = AdminResponses()
    private val empty = UnsafeBuffer(ByteArray(0))

    private fun answer(correlationId: Long, code: AdminResponseCode, message: String = "") =
        responses.onAdminResponse(1L, correlationId, AdminRequestType.SNAPSHOT, code, message, empty, 0, 0)

    @Test
    fun `an OK answer to this request confirms it`() {
        val outcome = requestSnapshot(responses, correlationId = 7L, timeout = Duration.ofSeconds(1),
            send = { true }, poll = { answer(7L, AdminResponseCode.OK); 1 })

        assertTrue(outcome.sent)
        assertTrue(outcome.confirmed)
        assertEquals(AdminResponseCode.OK, outcome.code)
    }

    @Test
    fun `a denial is reported with the cluster's message, not as a success`() {
        val outcome = requestSnapshot(responses, correlationId = 7L, timeout = Duration.ofSeconds(1),
            send = { true }, poll = { answer(7L, AdminResponseCode.UNAUTHORISED_ACCESS, "Execution of the SNAPSHOT request was not authorised"); 1 })

        assertTrue(outcome.sent)
        assertFalse(outcome.confirmed)
        assertEquals(AdminResponseCode.UNAUTHORISED_ACCESS, outcome.code)
        assertTrue("not authorised" in outcome.message)
    }

    @Test
    fun `an answer to another request is not this one's`() {
        val outcome = requestSnapshot(responses, correlationId = 7L, timeout = Duration.ofMillis(100),
            send = { true }, poll = { answer(8L, AdminResponseCode.OK); 1 })

        assertFalse(outcome.confirmed)
        assertEquals(null, outcome.code)
        assertTrue("no answer" in outcome.message)
    }

    @Test
    fun `no answer in time is a timeout, not a confirmation`() {
        val outcome = requestSnapshot(responses, correlationId = 7L, timeout = Duration.ofMillis(100),
            send = { true }, poll = { 0 })

        assertTrue(outcome.sent)
        assertFalse(outcome.confirmed)
        assertTrue("no answer" in outcome.message)
    }

    @Test
    fun `a request that could not be offered was not sent`() {
        val outcome = requestSnapshot(responses, correlationId = 7L, timeout = Duration.ofSeconds(1),
            send = { false }, poll = { error("must not poll for an answer to nothing") })

        assertFalse(outcome.sent)
        assertFalse(outcome.confirmed)
    }
}
