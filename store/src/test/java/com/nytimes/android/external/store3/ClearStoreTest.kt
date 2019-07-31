package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.store3.base.Clearable
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.pipeline.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.verify
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@FlowPreview
@RunWith(Parameterized::class)
class ClearStoreTest(
        storeType: TestStoreType
) {
    private val persister: ClearingPersister = mock()
    private val networkCalls = AtomicInteger(0)

    private val store: PipelineStore<BarCode, Int> = beginNonFlowingPipeline<BarCode, Int> { networkCalls.incrementAndGet() }
            .withNonFlowPersister(
                    reader = persister::read,
                    writer = {key,value->persister.write(key,value)},
                    delete =  TestStoreBuilder.SuspendWrapper(
                            persister::clear
                    )::apply
            )
            .withCache(MemoryPolicy.builder().setExpireAfterTimeUnit(TimeUnit.SECONDS).setExpireAfterWrite(5).build())

    @Test
    fun testClearSingleBarCode() = runBlocking<Unit> {
        // one request should produce one call
        val barcode = BarCode("type", "key")

        whenever(persister.read(barcode))
                .thenReturn(null) //read from disk on get
                .thenReturn(1) //read from disk after fetching from network
                .thenReturn(null) //read from disk after clearing
                .thenReturn(1) //read from disk after making additional network call
        whenever(persister.write(barcode, 1)).thenReturn(true)
        whenever(persister.write(barcode, 2)).thenReturn(true)

        val firstResult = store.get(barcode)
        assertThat(networkCalls.toInt()).isEqualTo(1)

        // after clearing the memory another call should be made
        store.clear(barcode)
        store.get(barcode)
        verify(persister).clear(barcode)
        assertThat(networkCalls.toInt()).isEqualTo(2)
    }

    @Test
    fun testClearAllBarCodes() = runBlocking<Unit> {
        val barcode1 = BarCode("type1", "key1")
        val barcode2 = BarCode("type2", "key2")

        whenever(persister.read(barcode1))
                .thenReturn(null) //read from disk
                .thenReturn(1) //read from disk after fetching from network
                .thenReturn(null) //read from disk after clearing disk cache
                .thenReturn(1) //read from disk after making additional network call
        whenever(persister.write(barcode1, 1)).thenReturn(true)
        whenever(persister.write(barcode1, 2)).thenReturn(true)

        whenever(persister.read(barcode2))
                .thenReturn(null) //read from disk
                .thenReturn(1) //read from disk after fetching from network
                .thenReturn(null) //read from disk after clearing disk cache
                .thenReturn(1) //read from disk after making additional network call

        whenever(persister.write(barcode2, 1)).thenReturn(true)
        whenever(persister.write(barcode2, 2)).thenReturn(true)

        // each request should produce one call
        store.get(barcode1)
        store.get(barcode2)
        assertThat(networkCalls.toInt()).isEqualTo(2)

        store.clear(barcode1)
        store.clear(barcode2)

        // after everything is cleared each request should produce another 2 calls
        store.get(barcode1)
        store.get(barcode2)
        assertThat(networkCalls.toInt()).isEqualTo(4)
    }

    @FlowPreview
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}

suspend fun <T, V> PipelineStore<T, V>.get(key: T) = stream(
        StoreRequest.cached(
                key = key,
                refresh = false
        )
).singleOrNull()

suspend fun <T, V> PipelineStore<T, V>.get(storeRequest: StoreRequest<T>) = stream(storeRequest).singleOrNull()
