package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Parser
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StoreWithParserTest(
        private val storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private val fetcher: Fetcher<String, BarCode> = mock()
    private val persister: Persister<String, BarCode> = mock()
    private val parser: Parser<String, String> = mock()

    private val barCode = BarCode("key", "value")

    @Test
    fun testSimple() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.fromPostParser(
                fetcher = fetcher,
                persister = persister,
                postParser = parser
        ).build(storeType)

        whenever(fetcher.fetch(barCode))
                .thenReturn(NETWORK)

        whenever(persister.read(barCode))
                .thenReturn(null)
                .thenReturn(DISK)

        whenever(persister.write(barCode, NETWORK))
                .thenReturn(true)

        whenever(parser.apply(DISK)).thenReturn(barCode.key)

        var value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(barCode.key)
        value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(barCode.key)
        verify(fetcher, times(1)).fetch(barCode)
    }

    @Test
    fun testSubclass() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.fromPostParser(
                fetcher = fetcher,
                persister = persister,
                postParser = parser
        ).build(storeType)
        whenever(fetcher.fetch(barCode))
                .thenReturn(NETWORK)

        whenever(persister.read(barCode))
                .thenReturn(null)
                .thenReturn(DISK)

        whenever(persister.write(barCode, NETWORK))
                .thenReturn(true)

        whenever(parser.apply(DISK)).thenReturn(barCode.key)

        var value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(barCode.key)
        value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(barCode.key)
        verify(fetcher, times(1)).fetch(barCode)
    }

    companion object {
        private const val DISK = "persister"
        private const val NETWORK = "fresh"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
