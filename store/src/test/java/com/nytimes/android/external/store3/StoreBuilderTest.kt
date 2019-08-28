package com.nytimes.android.external.store3


import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.wrappers.parser
import com.nytimes.android.external.store3.base.wrappers.persister
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Date

class StoreBuilderTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun testBuildersBuildWithCorrectTypes() = testScope.runBlockingTest {
        //test  is checking whether types are correct in builders
        val store: Store<Date, Int> = Store.from<String, Int> { key ->
            key.toString()
        }.persister(object : Persister<String, Int> {
            override suspend fun read(key: Int): String? {
                return key.toString()
            }

            override suspend fun write(key: Int, raw: String) = true
        }).parser { DATE }.open()

        val barCodeStore = Store.from<Date, BarCode> { DATE }.open()

        val keyStore = Store.from<Date, Int> { DATE }.open()
        var result = store.get(5)
        result = barCodeStore.get(BarCode("test", "5"))
        result = keyStore.get(5)
        assertThat(result).isNotNull()
    }

    companion object {
        private val DATE = Date()
    }
}
