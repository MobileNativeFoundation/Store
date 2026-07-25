@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.compose

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SkipEqualDataTest {
    private fun data(
        value: String,
        stale: Boolean = false,
        refreshing: Boolean = false,
        age: Duration = Duration.ZERO,
        origin: Origin = Origin.FETCHER,
    ): StoreResult<String> = TestStoreResults.data(
        value = value, origin = origin, age = age, isStale = stale, refreshing = refreshing,
    )

    @Test
    fun dropsStructurallyEqualConsecutiveData(): TestResult = runTest {
        val out = flowOf(data("a"), data("a"), data("a"), data("b"), data("b"))
            .skipEqualData().toList()
        assertEquals(listOf("a", "b"), out.map { (it as StoreResult.Data<String>).value })
    }

    @Test
    fun ageIsExcludedFromTheComparison(): TestResult = runTest {
        val out = flowOf(data("a", age = 0.milliseconds), data("a", age = 250.milliseconds))
            .skipEqualData().toList()
        assertEquals(1, out.size)
    }

    @Test
    fun flagChangesAreEmitted(): TestResult = runTest {
        val out = flowOf(data("a"), data("a", refreshing = true), data("a", refreshing = true, stale = true))
            .skipEqualData().toList()
        assertEquals(3, out.size)
    }

    @Test
    fun lifecycleSignalsAlwaysPass(): TestResult = runTest {
        val error = TestStoreResults.error(TestStoreResults.fetchError("boom"), servedStale = true)
        val out = flowOf(
            TestStoreResults.loading(), TestStoreResults.loading(),
            data("a"), TestStoreResults.revalidated(1.milliseconds),
            TestStoreResults.revalidated(1.milliseconds), error, error,
        ).skipEqualData().toList()
        assertEquals(7, out.size)
    }

    @Test
    fun dataSeparatedByLifecycleSignalReEmits(): TestResult = runTest {
        val out = flowOf(data("a"), TestStoreResults.loading(), data("a"))
            .skipEqualData().toList()
        assertEquals(3, out.size)
    }

    @Test
    fun customValueEquivalenceIsHonored(): TestResult = runTest {
        val out = flowOf(data("a"), data("A"), data("b"))
            .skipEqualData { x, y -> x.equals(y, ignoreCase = true) }.toList()
        assertEquals(listOf("a", "b"), out.map { (it as StoreResult.Data<String>).value })
    }
}
