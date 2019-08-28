package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.impl.BarCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class ClearStoreMemoryTest(
        storeType : TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private var networkCalls = 0
    private val store = TestStoreBuilder.from<BarCode, Int> {
        networkCalls ++
    }.build(storeType)

    @Test
    fun testClearSingleBarCode() = testScope.runBlockingTest {
        //one request should produce one call
        val barcode = BarCode("type", "key")
        store.get(barcode)
        assertThat(networkCalls).isEqualTo(1)

        // after clearing the memory another call should be made
        store.clear(barcode)
        store.get(barcode)
        assertThat(networkCalls).isEqualTo(2)
    }

    @Test
    fun testClearAllBarCodes() = testScope.runBlockingTest {
        val b1 = BarCode("type1", "key1")
        val b2 = BarCode("type2", "key2")

        //each request should produce one call
        store.get(b1)
        store.get(b2)
        assertThat(networkCalls).isEqualTo(2)

        store.clear(b1)
        store.clear(b2)

        //after everything is cleared each request should produce another 2 calls
        store.get(b1)
        store.get(b2)
        assertThat(networkCalls).isEqualTo(4)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
