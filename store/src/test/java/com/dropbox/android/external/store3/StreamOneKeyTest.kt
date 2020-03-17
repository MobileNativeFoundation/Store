package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.fresh
import com.dropbox.android.external.store4.get
import com.dropbox.android.external.store4.legacy.BarCode
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.broadcastIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@FlowPreview
@ExperimentalStdlibApi
@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StreamOneKeyTest(
    private val storeType: TestStoreType
) {

    val fetcher: Fetcher<String, BarCode> = mock()
    val persister: Persister<String, BarCode> = mock()
    private val barCode = BarCode("key", "value")
    private val barCode2 = BarCode("key2", "value2")
    private val testScope = TestCoroutineScope()

    private val store = TestStoreBuilder.from(
        scope = testScope,
        fetcher = fetcher,
        persister = persister
    ).build(storeType)

    @Before
    fun setUp() = runBlockingTest {
        whenever(fetcher.invoke(barCode))
            .thenReturn(TEST_ITEM)
            .thenReturn(TEST_ITEM2)

        whenever(persister.read(barCode))
            .let {
                // the backport stream method of Pipeline to Store does not skip disk so we
                // make sure disk returns empty value first

                it.thenReturn(null)
            }
            .thenReturn(TEST_ITEM)
            .thenReturn(TEST_ITEM2)

        whenever(persister.write(barCode, TEST_ITEM))
            .thenReturn(true)
        whenever(persister.write(barCode, TEST_ITEM2))
            .thenReturn(true)
    }

    @Suppress("UsePropertyAccessSyntax") // for assert isTrue() isFalse()
    @Test
    fun testStream() = testScope.runBlockingTest {
        val streamSubscription = store.stream(StoreRequest.skipMemory(
            key = barCode,
            refresh = true
        )).transform {
            it.throwIfError()
            it.dataOrNull()?.let {
                emit(it)
            }
        }
            .openChannelSubscription()
        try {

            assertThat(streamSubscription.isEmpty).isFalse()

            store.clear(barCode)

            assertThat(streamSubscription.poll()).isEqualTo(TEST_ITEM)
            // get for another barcode should not trigger a stream for barcode1
            whenever(fetcher.invoke(barCode2))
                .thenReturn(TEST_ITEM)
            whenever(persister.read(barCode2))
                .thenReturn(TEST_ITEM)
            whenever(persister.write(barCode2, TEST_ITEM))
                .thenReturn(true)
            store.get(barCode2)
            assertThat(streamSubscription.isEmpty).isTrue()

            // get a another barCode one and ensure subscribers are notified
            store.fresh(barCode)
            assertThat(streamSubscription.poll()).isEqualTo(TEST_ITEM2)
        } finally {
            streamSubscription.cancel()
        }
    }

    companion object {
        private const val TEST_ITEM = "test"
        private const val TEST_ITEM2 = "test2"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
fun <T> Flow<T>.openChannelSubscription() =
    broadcastIn(GlobalScope + Dispatchers.Unconfined).openSubscription()
