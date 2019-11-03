package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

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
        whenever(fetcher.fetch(barCode))
            .thenReturn(TEST_ITEM)
            .thenReturn(TEST_ITEM2)

        whenever(persister.read(barCode))
            .let {
                // the backport stream method of Pipeline to Store does not skip disk so we
                // make sure disk returns empty value first
                if (storeType != TestStoreType.Store) {
                    it.thenReturn(null)
                } else {
                    it
                }
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
        val streamSubscription = store.stream(barCode)
            .openChannelSubscription()
        try {
            if (storeType == TestStoreType.Store) {
                //stream doesn't invoke get anymore so when we call it the channel is empty
                assertThat(streamSubscription.isEmpty).isTrue()
            } else {
                // for pipeline store, there is no `get` so it is not empty
                assertThat(streamSubscription.isEmpty).isFalse()
            }


            store.clear(barCode)

            if (storeType == TestStoreType.Store) {
                //fresh should notify subscribers in Store. In Pipeline, calling stream
                // will already trigger a get, we don't want another here
                store.fresh(barCode)
            }

            assertThat(streamSubscription.poll()).isEqualTo(TEST_ITEM)
            //get for another barcode should not trigger a stream for barcode1
            whenever(fetcher.fetch(barCode2))
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
