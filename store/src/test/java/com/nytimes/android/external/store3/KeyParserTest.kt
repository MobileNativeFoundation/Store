package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.util.KeyParser
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@FlowPreview
@RunWith(Parameterized::class)
class KeyParserTest(
        storeType: TestStoreType
) {
    private val store = TestStoreBuilder.from(
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
    fun testStoreWithKeyParserFuncNoPersister() = runBlocking<Unit> {
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
