package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.broadcastIn
import org.assertj.core.api.Assertions.assertThat
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
@FlowPreview
@RunWith(Parameterized::class)
class StreamTest(
        private val storeType: TestStoreType
) {

    private val fetcher: Fetcher<String, BarCode> = mock()
    private val persister: Persister<String, BarCode> = mock()

    private val barCode = BarCode("key", "value")

    private val store = TestStoreBuilder.from(
            fetcher = fetcher,
            persister = persister
    ).build(storeType)

    @Before
    fun setUp() = runBlocking<Unit> {
        whenever(fetcher.fetch(barCode)).thenReturn(TEST_ITEM)

        whenever(persister.read(barCode))
                .thenReturn(null)
                .thenReturn(TEST_ITEM)

        whenever(persister.write(barCode, TEST_ITEM))
                .thenReturn(true)
    }

    @Suppress("UsePropertyAccessSyntax")// for isTrue() / isFalse()
    @Test
    fun testStream() = runBlocking<Unit> {
        if (storeType == TestStoreType.Pipeline) {
            throw AssumptionViolatedException("Pipeline store does not support stream() no arg")
        }
        val streamSubscription = store.stream().openChannelSubscription()
        try {
            assertThat(streamSubscription.isEmpty).isTrue()
            store.get(barCode)
            assertThat(streamSubscription.first()).isEqualTo(barCode to TEST_ITEM)
        } finally {
            streamSubscription.cancel()
        }
    }

    @Suppress("UsePropertyAccessSyntax")// for isTrue() / isFalse()
    @Test
    fun testStreamEmitsOnlyFreshData() = runBlocking<Unit> {
        if (storeType == TestStoreType.Pipeline) {
            throw AssumptionViolatedException("Pipeline store does not support stream() no arg")
        }
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

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}

@ExperimentalCoroutinesApi
@FlowPreview
fun <T> Flow<T>.openChannelSubscription() =
        broadcastIn(GlobalScope + Dispatchers.Unconfined).openSubscription()