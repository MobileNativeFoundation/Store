@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

internal class SqlDelightCrashWindowTest {
    @Test
    fun writeCommittedButRecordSuccessLost_ageIsKnownOnRestart() = runTest {
        withHarness { harness ->
            val clock = TestWallClock(startEpochMillis = WRITE_TIME)
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness, clock)
            var fetchCalls = 0
            val fetch: suspend (SqlTestKey) -> String = {
                fetchCalls += 1
                "fetched-$fetchCalls"
            }

            sourceOfTruth.write(KEY_A, "v1")

            val sidecarAdoptingStore = restartedStore(harness, clock, fetch)
            try {
                assertEquals(
                    "v1",
                    sidecarAdoptingStore.get(KEY_A, Freshness.MaxAge(1.hours)),
                )
                assertEquals(0, fetchCalls)
            } finally {
                sidecarAdoptingStore.close()
            }

            harness.executeRaw(
                "DELETE FROM store6_meta WHERE namespace = 'users' AND canonical_id = 'a'",
            )
            assertNull(harness.metaRow(KEY_A.ns, KEY_A.id))

            val metaLessStore = restartedStore(harness, clock, fetch)
            try {
                assertEquals(
                    "fetched-1",
                    metaLessStore.get(KEY_A, Freshness.MaxAge(1.hours)),
                )
                assertEquals(1, fetchCalls)
            } finally {
                metaLessStore.close()
            }
        }
    }

    @Test
    fun writeStamp_neverAssignsSuccessSequence() = runTest {
        withHarness { harness ->
            val clock = TestWallClock(startEpochMillis = WRITE_TIME)
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness, clock)
            val bookkeeper = SqlDelightBookkeeper(harness.driver, harness.transacter)

            bookkeeper.advanceStaleWatermark(KEY_A.namespace)
            sourceOfTruth.write(KEY_A, "v1")

            val status = assertNotNull(bookkeeper.status(KEY_A))
            assertEquals(WRITE_TIME, status.meta?.writtenAtEpochMillis)
            assertNull(status.lastSuccessSequence)
            assertTrue(status.durablyStale)
        }
    }

    private fun restartedStore(
        harness: SqlHarness,
        clock: TestWallClock,
        fetch: suspend (SqlTestKey) -> String,
    ): Store<SqlTestKey, String> =
        store {
            fetcher(fetch)
            persistence(sqlDelightTestSot(harness, clock))
            bookkeeper(SqlDelightBookkeeper(harness.driver, harness.transacter))
            wallClock(clock)
        }

    private suspend fun <R> withHarness(block: suspend (SqlHarness) -> R): R {
        val harness = freshHarness()
        return try {
            block(harness)
        } finally {
            harness.driver.close()
        }
    }

    private companion object {
        val KEY_A = SqlTestKey(ns = "users", id = "a")
        const val WRITE_TIME = 10_000L
    }
}
