package org.mobilenativefoundation.store6.sqldelight.internal

import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

internal class SynchronousTransactionsTest {
    @Test
    fun synchronousSuccessReturnsValue() {
        assertEquals(42, runNonSuspending { 42 })
    }

    @Test
    fun inlineContinuationResumeStillCountsAsSynchronous() {
        assertEquals(
            42,
            runNonSuspending {
                suspendCoroutine { continuation -> continuation.resume(42) }
            },
        )
    }

    @Test
    fun synchronousFailureIsRethrown() {
        val expected = TestFailure()

        val actual =
            assertFailsWith<TestFailure> {
                runNonSuspending<Unit> { throw expected }
            }

        assertSame(expected, actual)
    }

    @Test
    fun genuinelySuspendingBlockFailsFast() {
        val failure =
            assertFailsWith<IllegalStateException> {
                runNonSuspending<Unit> {
                    suspendCoroutine<Unit> { }
                }
            }

        assertEquals(
            "withTransaction block suspended while executing against a synchronous SQLDelight " +
                "driver for this store. Keep the block to synchronous same-database statements " +
                "(this adapter's write/delete and other SQLDelight statements); move asynchronous " +
                "work outside withTransaction.",
            failure.message,
        )
    }

    private class TestFailure : RuntimeException()
}
