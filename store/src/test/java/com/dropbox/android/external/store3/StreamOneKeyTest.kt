package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.fresh
import com.dropbox.android.external.store4.get
import com.dropbox.android.external.store4.impl.operators.mapIndexed
import com.dropbox.android.external.store4.testutil.FakeFetcher
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.time.ExperimentalTime

@ExperimentalTime
@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StreamOneKeyTest(
    storeType: TestStoreType
) {

    private var reader: (Pair<String, String>) -> String = mock()
    private var writer: (Pair<String, String>, String) -> Boolean = mock()
    private val barCode = "key" to "value"
    private val barCode2 = "key2" to "value2"
    private val testScope = TestCoroutineScope()

    private val fetcher = FakeFetcher(
        barCode to TEST_ITEM,
        barCode to TEST_ITEM2,
        barCode2 to TEST_ITEM
    )

    private val store = TestStoreBuilder.from(
        scope = testScope,
        fetcher = fetcher,
        reader = reader,
        writer = writer
    ).build(storeType)

    @Before
    fun setUp() = runBlockingTest {

        whenever(reader(barCode))
            .run {
                // the backport stream method of Pipeline to Store does not skip disk so we
                // make sure disk returns empty value first
                thenReturn(null)
            }
            .thenReturn(TEST_ITEM)
            .thenReturn(TEST_ITEM2)

        whenever(writer(barCode, TEST_ITEM))
            .thenReturn(true)
        whenever(writer(barCode, TEST_ITEM2))
            .thenReturn(true)
    }

    @Test
    fun testStream() = testScope.runBlockingTest {
        val streamSubscription = store.stream(
            StoreRequest.skipMemory(
                key = barCode,
                refresh = true
            )
        ).transform {
            it.throwIfError()
            it.dataOrNull()?.let {
                emit(it)
            }
        }.mapIndexed { index, data ->
            index to data
        }.stateIn(testScope)
        assertThat(streamSubscription.value).isEqualTo(0 to TEST_ITEM)

        store.clear(barCode)

        assertThat(streamSubscription.value).isEqualTo(0 to TEST_ITEM)
        // get for another barcode should not trigger a stream for barcode1
        whenever(reader(barCode2))
            .thenReturn(TEST_ITEM)
        whenever(writer(barCode2, TEST_ITEM))
            .thenReturn(true)
        store.get(barCode2)
        assertThat(streamSubscription.value).isEqualTo(0 to TEST_ITEM)

        // get a another barCode one and ensure subscribers are notified
        store.fresh(barCode)
        assertThat(streamSubscription.value).isEqualTo(1 to TEST_ITEM2)
    }

    companion object {
        private const val TEST_ITEM = "test"
        private const val TEST_ITEM2 = "test2"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
