@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

internal class SqlDelightDurableMaintenanceTest {
    @Test
    fun invalidate_markIsObservedByFreshStoreUsingSharedCollaborators() = runTest {
        withHarness { harness ->
            var calls = 0
            val clock = TestWallClock(startEpochMillis = 100L)
            fun buildStore(): Store<SqlTestKey, String> =
                restartedStore(harness, clock) { "v${++calls}" }

            val first = buildStore()
            try {
                assertEquals("v1", first.get(KEY_1))
                first.invalidate(KEY_1)
            } finally {
                first.close()
            }

            val second = buildStore()
            try {
                assertEquals("v2", second.get(KEY_1, Freshness.MaxAge(1.hours)))
                assertEquals(2, calls)
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun invalidateNamespace_watermarkIsObservedForKeyUnseenByFreshStore() = runTest {
        withHarness { harness ->
            var calls = 0
            val clock = TestWallClock(startEpochMillis = 100L)
            fun buildStore(): Store<SqlTestKey, String> =
                restartedStore(harness, clock) { "v${++calls}" }

            val first = buildStore()
            try {
                assertEquals("v1", first.get(KEY_A1))
                assertEquals("v2", first.get(KEY_A2))
                first.invalidateNamespace(StoreNamespace("a"))
            } finally {
                first.close()
            }

            val second = buildStore()
            try {
                assertEquals("v3", second.get(KEY_A2, Freshness.MaxAge(1.hours)))
                assertEquals(3, calls)
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun invalidateAll_globalWatermarkIsObservedAcrossNamespacesByFreshStore() = runTest {
        withHarness { harness ->
            var calls = 0
            val clock = TestWallClock(startEpochMillis = 100L)
            fun buildStore(): Store<SqlTestKey, String> =
                restartedStore(harness, clock) { "v${++calls}" }

            val first = buildStore()
            try {
                assertEquals("v1", first.get(KEY_A1))
                assertEquals("v2", first.get(KEY_B1))
                first.invalidateAll()
            } finally {
                first.close()
            }

            val second = buildStore()
            try {
                assertEquals("v3", second.get(KEY_A1, Freshness.MaxAge(1.hours)))
                assertEquals("v4", second.get(KEY_B1, Freshness.MaxAge(1.hours)))
                assertEquals(4, calls)
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun freshSidecarWithoutInvalidation_servesHydratedValueAfterFreshStoreStarts() = runTest {
        withHarness { harness ->
            var calls = 0
            val clock = TestWallClock(startEpochMillis = 100L)
            fun buildStore(): Store<SqlTestKey, String> =
                restartedStore(harness, clock) { "v${++calls}" }

            val first = buildStore()
            try {
                assertEquals("v1", first.get(KEY_1))
            } finally {
                first.close()
            }

            val second = buildStore()
            try {
                assertEquals("v1", second.get(KEY_1, Freshness.MaxAge(1.hours)))
                assertEquals(1, calls)
            } finally {
                second.close()
            }
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
        val KEY_1 = SqlTestKey(ns = "test", id = "1")
        val KEY_A1 = SqlTestKey(ns = "a", id = "1")
        val KEY_A2 = SqlTestKey(ns = "a", id = "2")
        val KEY_B1 = SqlTestKey(ns = "b", id = "1")
    }
}
