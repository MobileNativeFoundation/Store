@file:OptIn(
    ExperimentalStoreApi::class,
    DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.devtoolsdemo

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class DemoFetcherTest {
    @Test
    fun failureToggleProducesFetcherError(): TestResult = runTest {
        val controls = DemoControls().apply { failFetches.value = true }
        val fetcher = DemoFetcher(controls)

        assertIs<FetcherResult.Error>(fetcher.fetch(UserKey("1"), etag = null))
    }

    @Test
    fun unscriptedFetchesProduceVersionedUsersAndScriptedResultsWin(): TestResult = runTest {
        val controls = DemoControls().apply { latencyMillis.value = 3000L }
        val fetcher = DemoFetcher(controls)
        val key = UserKey("1")

        val first = fetcher.fetch(key, etag = null)
        assertIs<FetcherResult.Success<User>>(first)
        assertEquals("User 1 (v1)", first.value.name)
        assertEquals(3000L, currentTime)

        val second = fetcher.fetch(key, etag = null)
        assertIs<FetcherResult.Success<User>>(second)
        assertEquals("User 1 (v2)", second.value.name)
        assertEquals(6000L, currentTime)

        fetcher.delegate.enqueue(key, FetcherResult.Success(User("1", "Scripted")))
        val third = fetcher.fetch(key, etag = null)
        assertIs<FetcherResult.Success<User>>(third)
        assertEquals("Scripted", third.value.name)
        assertEquals(9000L, currentTime)
    }
}

// One file-private 25s runTest shadow, no nested wall-clock waits.
private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
