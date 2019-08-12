package com.nytimes.android.sample

import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.StoreBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test

class NoNetworkTest {
    private val store = StoreBuilder.barcode<Any>()
            .fetcher { throw EXCEPTION }
            .open()
    private val testScope = TestCoroutineScope()

    @Test(expected = java.lang.Exception::class)
    fun testNoNetwork() = testScope.runBlockingTest {
        store.get(BarCode("test", "test"))
    }

    companion object {
        private val EXCEPTION = RuntimeException()
    }
}
