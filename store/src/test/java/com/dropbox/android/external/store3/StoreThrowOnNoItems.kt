package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.get
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicInteger

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StoreThrowOnNoItems(
    private val storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private val counter = AtomicInteger(0)
    private val fetcher: Fetcher<Pair<String, String>, String> = mock()
    private val barCode = "key" to "value"

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
