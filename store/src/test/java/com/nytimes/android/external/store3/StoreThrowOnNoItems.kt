package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.*
import com.nytimes.android.external.cache3.CacheBuilder
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.StalePolicy
import com.nytimes.android.external.store3.util.NoopPersister
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalCoroutinesApi
@FlowPreview
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
                fetcher = fetcher
        ).build(storeType)

        whenever(fetcher.fetch(barCode))
                .thenThrow(NoSuchElementException())

       try {
           simpleStore.get(barCode)
           fail("exception not thrown when no items emitted from fetcher")

       }
       catch(e:NoSuchElementException){
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
