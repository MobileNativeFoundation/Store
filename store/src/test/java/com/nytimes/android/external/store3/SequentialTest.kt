package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.wrappers.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import kotlin.random.Random

class SequentialTest {

    var networkCalls = 0
    private val store = Store<Int, BarCode> { networkCalls++ }
            .addCache()
            .addInflight()

    @Test
    fun sequentially() = runBlocking<Unit> {
        val b = BarCode("one", "two")
        store.get(b)
        store.get(b)

        assertThat(networkCalls).isEqualTo(1)
    }

    @Test
    fun parallel() = runBlocking<Unit> {
        val b = BarCode("one", "two")
        val deferred = async { store.get(b) }
        store.get(b)
        deferred.await()

        assertThat(networkCalls).isEqualTo(1)
    }

    @Test
    fun cacheWithParser() = runBlocking<Unit> {
        val persister: Persister<String, Int> = object : Persister<String, Int> {
            private val map = mutableMapOf<Int, String>()
            override suspend fun read(key: Int): String? = map[key]

            override suspend fun write(key: Int, raw: String): Boolean {
                map[key] = raw
                return true
            }
        }

        val store: Store<String, Int> = Store<Int, Int> { it + Random.nextInt() }
                .addParser { it.toString() }
                .addPersister(persister)
                .addCache()
                .addInflight()

        val v1 = store.get(4)
        val v2 = store.get(4)
        assertThat(v1).isEqualTo(v2)
    }
}
