package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.Store
import junit.framework.Assert.fail
import kotlinx.coroutines.runBlocking
import org.junit.Test


class DontCacheErrorsTest {

    private var shouldThrow: Boolean = false
    private val store: Store<Int, BarCode> = Store.from<Int, BarCode> {
        if (shouldThrow) {
            throw RuntimeException()
        } else {
            0
        }
    }.open()

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
}
