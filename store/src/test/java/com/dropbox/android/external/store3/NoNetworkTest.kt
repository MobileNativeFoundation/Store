package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.get
import com.dropbox.android.external.store4.legacy.BarCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class NoNetworkTest(
    storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private val store: Store<BarCode, out Any> = TestStoreBuilder.from<BarCode, Any>(testScope) {
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
