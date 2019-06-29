package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.wrappers.Store
import com.nytimes.android.external.store3.base.wrappers.addParser
import com.nytimes.android.external.store3.util.KeyParser
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test


class KeyParserTest {
    private val store = Store<String, Int> { NETWORK }.addParser(object : KeyParser<Int, String, String> {
        override suspend fun apply(key: Int, raw: String): String {
            return raw + key
        }
    })

    @Test
    fun testStoreWithKeyParserFuncNoPersister() = runBlocking<Unit> {
        assertThat(store.get(KEY)).isEqualTo(NETWORK + KEY)
    }

    companion object {
        private const val NETWORK = "Network"
        private const val KEY = 5
    }
}
