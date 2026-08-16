@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.composedemo

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DemoFetcherTest {
    @Test
    fun failureToggleProducesFetcherError() = runTest {
        val controls = DemoControls().apply { failFetches.value = true }
        val fetcher = DemoFetcher(controls)
        assertIs<FetcherResult.Error>(fetcher.fetch(UserKey("1"), etag = null))
    }

    @Test
    fun unscriptedFetchesProduceVersionedUsersAndScriptedResultsWin() = runTest {
        val controls = DemoControls().apply { latencyMillis.value = 3000L }
        val fetcher = DemoFetcher(controls)
        val key = UserKey("1")
        val first = fetcher.fetch(key, etag = null)
        assertIs<FetcherResult.Success<User>>(first)
        assertEquals("User 1 (v1)", first.value.name)
        assertEquals(3000L * 1, currentTime) // virtual latency honored
        fetcher.delegate.enqueue(key, FetcherResult.Success(User("1", "Scripted")))
        val second = fetcher.fetch(key, etag = null)
        assertIs<FetcherResult.Success<User>>(second)
        assertEquals("Scripted", second.value.name)
    }
}
