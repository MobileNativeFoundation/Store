package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class SeamFetcherTest {
    @Test
    fun seamFetcher_receivesEtagOnConditionalPlan() = runTest {
        // etags is mutated on the fetch coroutine (Dispatchers.Default) and read here only after the
        // corresponding get() returned — the FetchTicket completion edge orders every mutation
        // before the read (the T3 placement pin formalizes this happens-before).
        val etags = mutableListOf<String?>()
        val store =
            store<TestKey, String> {
                fetcher(
                    object : Fetcher<TestKey, String> {
                        override suspend fun fetch(
                            key: TestKey,
                            etag: String?,
                        ): FetcherResult<String> {
                            etags += etag
                            return FetcherResult.Success(
                                "v${etags.size}",
                                etag = "tag-${etags.size}",
                            )
                        }
                    },
                )
            }

        assertEquals("v1", store.get(TestKey("1")))
        assertEquals("v2", store.get(TestKey("1"), Freshness.MustBeFresh))
        assertEquals(listOf(null, "tag-1"), etags)
        store.close()
    }
}
