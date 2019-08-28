package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.impl.BarCode
import junit.framework.Assert.fail
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DontCacheErrorsTest(
        storeType: TestStoreType
) {

    private var shouldThrow: Boolean = false
    private val store = TestStoreBuilder.from<BarCode, Int> {
        if (shouldThrow) {
            throw RuntimeException()
        } else {
            0
        }
    }.build(storeType)

    @Test
    fun testStoreDoesntCacheErrors() = runBlocking<Unit> {
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
