package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.wrappers.persister
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.broadcastIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test

class StreamTest {

    private val fetcher: Fetcher<String, BarCode> = mock()
    private val persister: Persister<String, BarCode> = mock()

    private val barCode = BarCode("key", "value")

    private val store = Store.from(fetcher).persister(persister).open()

    @Before
    fun setUp() = runBlocking<Unit> {
        whenever(fetcher.fetch(barCode)).thenReturn(TEST_ITEM)

        whenever(persister.read(barCode))
                .thenReturn(null)
                .thenReturn(TEST_ITEM)

        whenever(persister.write(barCode, TEST_ITEM))
                .thenReturn(true)
    }

    @Test
    fun testStream() = runBlocking<Unit> {
        val streamSubscription = store.stream().openChannelSubscription()
        try {
            assertThat(streamSubscription.isEmpty).isTrue()
            store.get(barCode)
            assertThat(streamSubscription.first()).isEqualTo(barCode to TEST_ITEM)
        } finally {
            streamSubscription.cancel()
        }
    }

    @Test
    fun testStreamEmitsOnlyFreshData() = runBlocking<Unit> {
        store.get(barCode)
        val streamSubscription = store.stream().openChannelSubscription()
        try {
            assertThat(streamSubscription.isEmpty).isTrue()
        } finally {
            streamSubscription.cancel()
        }
    }

    companion object {
        private const val TEST_ITEM = "test"
    }
}

fun <T> Flow<T>.openChannelSubscription() =
        broadcastIn(GlobalScope + Dispatchers.Unconfined).openSubscription()