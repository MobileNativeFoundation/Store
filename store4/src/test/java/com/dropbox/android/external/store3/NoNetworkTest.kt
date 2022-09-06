package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.get
import com.google.common.truth.Truth.assertThat
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Assert.fail
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

    @OptIn(ExperimentalTime::class)
    private val store: Store<Pair<String, String>, out Any> = TestStoreBuilder.from<Pair<String, String>, Any>(
        testScope,
        fetcher = Fetcher.of {
            throw EXCEPTION
        }
    ).build(storeType)

    @Test
    fun testNoNetwork() = testScope.runBlockingTest {
        try {
            store.get("test" to "test")
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
