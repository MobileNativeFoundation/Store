@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Driver failures remain visible to status callers while operational writes absorb ordinary
 * storage failures and kotlin.Error propagates.
 */
internal class SqlDelightBookkeeperFailureTest {
    @Test
    fun status_runtimeStorageFailure_propagates() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = IllegalStateException("sqlite busy")

            val failure = assertFailsWith<IllegalStateException> { bookkeeper.status(KEY) }
            assertEquals("sqlite busy", failure.message)
        }
    }

    @Test
    fun status_failureAndRecovery_preserveExistingDurableStaleness() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            bookkeeper.recordSuccess(KEY, TestStoreMeta(1L, "e1"))
            bookkeeper.markStale(KEY)
            faultDriver.fault = IllegalStateException("temporarily unavailable")
            assertFailsWith<IllegalStateException> { bookkeeper.status(KEY) }
            faultDriver.fault = null
            val recovered = assertNotNull(bookkeeper.status(KEY))
            assertTrue(recovered.durablyStale)
            assertEquals("e1", recovered.meta?.etag)
        }
    }

    @Test
    fun status_virtualMachineError_propagates() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = SyntheticVmError()

            assertFailsWith<SyntheticVmError> { bookkeeper.status(KEY) }
        }
    }

    @Test
    fun recordFailure_runtimeStorageFailure_absorbedAndGateRecovers() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = IllegalStateException("disk io")
            bookkeeper.recordFailure(KEY, 10L)

            faultDriver.fault = null
            bookkeeper.recordSuccess(KEY, TestStoreMeta(1L, "e1"))
            assertNotNull(bookkeeper.status(KEY))
        }
    }

    @Test
    fun recordSuccess_virtualMachineError_propagates() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = SyntheticVmError()

            assertFailsWith<SyntheticVmError> {
                bookkeeper.recordSuccess(KEY, TestStoreMeta(1L, "e1"))
            }
        }
    }

    private suspend fun <R> withFaultyBookkeeper(
        block: suspend (SqlDelightBookkeeper, FaultDriver) -> R,
    ): R {
        val harness = freshHarness()
        val faultDriver = FaultDriver(harness.driver)
        val bookkeeper = SqlDelightBookkeeper(faultDriver, harness.transacter)
        return try {
            block(bookkeeper, faultDriver)
        } finally {
            harness.driver.close()
        }
    }

    private companion object {
        val KEY = SqlTestKey(ns = "failure", id = "key")
    }
}

/** Delegates every driver member until armed; armed faults fire on query/execute calls only. */
private class FaultDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var fault: Throwable? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        throwIfArmed()
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        throwIfArmed()
        return delegate.execute(identifier, sql, parameters, binders)
    }

    private fun throwIfArmed() {
        fault?.let { throw it }
    }
}

private class SyntheticVmError : Error()
