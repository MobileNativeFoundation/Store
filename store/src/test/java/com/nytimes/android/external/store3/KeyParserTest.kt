package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.wrappers.parser
import com.nytimes.android.external.store3.util.KeyParser
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test


class KeyParserTest {
    private val store = Store.from<String, Int> { NETWORK }.parser(object : KeyParser<Int, String, String> {
        override suspend fun apply(key: Int, raw: String): String {
            return raw + key
        }
    }).open()

    @Test
    fun testStoreWithKeyParserFuncNoPersister() = runBlocking<Unit> {
        assertThat(store.get(KEY)).isEqualTo(NETWORK + KEY)
    }

    companion object {
        private const val NETWORK = "Network"
        private const val KEY = 5
    }
}
