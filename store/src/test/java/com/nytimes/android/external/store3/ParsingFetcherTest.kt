package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Parser
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@FlowPreview
@RunWith(Parameterized::class)
class ParsingFetcherTest(
        private val storeType: TestStoreType
) {

    private val fetcher: Fetcher<String, BarCode> = mock()
    private val parser: Parser<String, String> = mock()
    private val persister: Persister<String, BarCode> = mock()
    private val barCode = BarCode("key", "value")

    @Test
    fun testPersistFetcher() = runBlocking<Unit> {
        val simpleStore = TestStoreBuilder.from(
                fetcher = fetcher,
                fetchParser = parser,
                persister = persister
        ).build(storeType)

        whenever(fetcher.fetch(barCode))
                .thenReturn(RAW_DATA)

        whenever(parser.apply(RAW_DATA))
                .thenReturn(PARSED)

        whenever(persister.read(barCode))
                .thenReturn(PARSED)

        whenever(persister.write(barCode, PARSED))
                .thenReturn(true)

        val value = simpleStore.fresh(barCode)

        assertThat(value).isEqualTo(PARSED)

        verify(fetcher, times(1)).fetch(barCode)
        verify(parser, times(1)).apply(RAW_DATA)

        verify(persister, times(1)).write(barCode, PARSED)
    }

    companion object {
        private const val RAW_DATA = "Test data."
        private const val PARSED = "DATA PARSED"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
