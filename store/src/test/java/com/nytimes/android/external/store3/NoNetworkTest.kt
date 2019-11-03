package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.Store
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NoNetworkTest(
        storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private val store: Store<out Any, BarCode> = TestStoreBuilder.from<BarCode, Any>(testScope) {
        throw EXCEPTION
    }.build(storeType)


    @Test
    fun testNoNetwork() = testScope.runBlockingTest {
        try {
            store.get(BarCode("test", "test"))
            fail("Exception not thrown")
        } catch (e: Exception) {
            assertThat(e.message).isEqualTo(EXCEPTION.message)
        }
    }

    companion object {
        private val EXCEPTION = RuntimeException("abc")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
