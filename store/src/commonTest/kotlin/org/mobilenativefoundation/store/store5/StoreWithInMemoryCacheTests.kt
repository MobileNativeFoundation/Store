package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.minutes

@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
class StoreWithInMemoryCacheTests {
    private val testScope = TestScope()

    @Test
    fun storeRequestsCanCompleteWhenInMemoryCacheWithAccessExpiryIsAtTheMaximumSize() = testScope.runTest {
        val store = StoreBuilder
            .from(Fetcher.of { _: Int -> "result" })
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .setExpireAfterAccess(10.minutes)
                    .setMaxSize(1)
                    .build()
            )
            .build()

        val a = store.get(0)
        val b = store.get(0)
        val c = store.get(1)
        val d = store.get(2)

        assertEquals("result", a)
        assertEquals("result", b)
        assertEquals("result", c)
        assertEquals("result", d)
    }
}
