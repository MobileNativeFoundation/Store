package com.dropbox.android.external.store3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.dropbox.android.external.store4.get
import com.dropbox.android.external.store4.legacy.BarCode
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.verify
import java.util.concurrent.atomic.AtomicInteger

@RunWith(Parameterized::class)
class ClearStoreTest(
    storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()

    private val persister: ClearingPersister = mock()
    private val networkCalls = AtomicInteger(0)

    private val store = TestStoreBuilder.from(
        scope = testScope,
        fetcher = {
            networkCalls.incrementAndGet()
        },
        persister = persister
    ).build(storeType)

    @Test
    fun testClearSingleBarCode() = testScope.runBlockingTest {
        // one request should produce one call
        val barcode = BarCode("type", "key")

        whenever(persister.read(barcode))
            .thenReturn(null) // read from disk on get
            .thenReturn(1) // read from disk after fetching from network
            .thenReturn(null) // read from disk after clearing
            .thenReturn(1) // read from disk after making additional network call
        whenever(persister.write(barcode, 1)).thenReturn(true)
        whenever(persister.write(barcode, 2)).thenReturn(true)

        store.get(barcode)
        assertThat(networkCalls.toInt()).isEqualTo(1)

        // after clearing the memory another call should be made
        store.clear(barcode)
        store.get(barcode)
        verify(persister).clear(barcode)
        assertThat(networkCalls.toInt()).isEqualTo(2)
    }

    @Test
    fun testClearAllBarCodes() = testScope.runBlockingTest {
        val barcode1 = BarCode("type1", "key1")
        val barcode2 = BarCode("type2", "key2")

        whenever(persister.read(barcode1))
            .thenReturn(null) // read from disk
            .thenReturn(1) // read from disk after fetching from network
            .thenReturn(null) // read from disk after clearing disk cache
            .thenReturn(1) // read from disk after making additional network call
        whenever(persister.write(barcode1, 1)).thenReturn(true)
        whenever(persister.write(barcode1, 2)).thenReturn(true)

        whenever(persister.read(barcode2))
            .thenReturn(null) // read from disk
            .thenReturn(1) // read from disk after fetching from network
            .thenReturn(null) // read from disk after clearing disk cache
            .thenReturn(1) // read from disk after making additional network call

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

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
