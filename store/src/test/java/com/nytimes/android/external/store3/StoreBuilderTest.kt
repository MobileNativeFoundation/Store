package com.nytimes.android.external.store3


import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.wrappers.Store
import com.nytimes.android.external.store3.base.wrappers.addParser
import com.nytimes.android.external.store3.base.wrappers.addPersister
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.*

class StoreBuilderTest {

    @Test
    fun testBuildersBuildWithCorrectTypes() = runBlocking<Unit> {
        //test  is checking whether types are correct in builders
        val store: Store<Date, Int> = Store<String, Int> { key ->
            key.toString()
        }.addPersister(object : Persister<String, Int> {
            override suspend fun read(key: Int): String? {
                return key.toString()
            }

            override suspend fun write(key: Int, raw: String) = true
        }).addParser { DATE }

        val barCodeStore: Store<Date, BarCode> = Store { DATE }

        val keyStore: Store<Date, Int> = Store { DATE }
        var result = store.get(5)
        result = barCodeStore.get(BarCode("test", "5"))
        result = keyStore.get(5)
        assertThat(result).isNotNull()
    }

    companion object {
        private val DATE = Date()
    }
}
