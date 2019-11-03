package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store4.legacy.BarCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StoreThrowOnNoItems(
    private val storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private val counter = AtomicInteger(0)
    private val fetcher: Fetcher<String, BarCode> = mock()
    private var persister: Persister<String, BarCode> = mock()
    private val barCode = BarCode("key", "value")

    @Test
    fun testShouldThrowOnFetcherEmitsNoSuckElementException() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
            scope = testScope,
            fetcher = fetcher
        ).build(storeType)

        whenever(fetcher.invoke(barCode))
            .thenThrow(NoSuchElementException())

        try {
            val unexpected = simpleStore.get(barCode)
            fail("exception not thrown when no items emitted from fetcher $unexpected")
        } catch (e: NoSuchElementException) {
            assertThat(e).isInstanceOf(NoSuchElementException::class.java)
        }
    }

    companion object {
        private const val DISK = "disk"
        private const val NETWORK = "fresh"
        private const val MEMORY = "memory"
        private const val ERROR = "error!!"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
