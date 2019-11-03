package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.impl.BarCode
import junit.framework.Assert.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(Parameterized::class)
class DontCacheErrorsTest(
        storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private var shouldThrow: Boolean = false
    // TODO move to test coroutine scope
    private val store = TestStoreBuilder.from<BarCode, Int>(testScope) {
        if (shouldThrow) {
            throw RuntimeException()
        } else {
            0
        }
    }.build(storeType)

    @Test
    fun testStoreDoesntCacheErrors() = testScope.runBlockingTest {
        val barcode = BarCode("bar", "code")

        shouldThrow = true

        try {
            store.get(barcode)
            fail()
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }

        shouldThrow = false
        store.get(barcode)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
