package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.get
import com.dropbox.android.external.store4.legacy.BarCode
import junit.framework.Assert.fail
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

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
        } catch (_: RuntimeException) {
            // expected
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
