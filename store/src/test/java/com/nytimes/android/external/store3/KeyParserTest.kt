package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.util.KeyParser
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class KeyParserTest(
    storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()

    private val store = TestStoreBuilder.from(
        scope = testScope,
        fetcher = {
            NETWORK
        },
        fetchParser = object : KeyParser<Int, String, String> {
            override suspend fun apply(key: Int, raw: String): String {
                return raw + key
            }
        }
    ).build(storeType)

    @Test
    fun testStoreWithKeyParserFuncNoPersister() = testScope.runBlockingTest {
        assertThat(store.get(KEY)).isEqualTo(NETWORK + KEY)
    }

    companion object {
        private const val NETWORK = "Network"
        private const val KEY = 5

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
