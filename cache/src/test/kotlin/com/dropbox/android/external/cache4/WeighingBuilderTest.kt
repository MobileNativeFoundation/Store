package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import kotlin.time.ExperimentalTime
import org.junit.Assert.assertThrows
import org.junit.Test
@ExperimentalTime
class WeighingBuilderTest {
    val MAX_SIZE = 100L
    @Test
    fun `weighing builder with max weight of zero and default weigher throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .buildWithWeigher<Any, Any>(OneWeigher(), -1)
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "maximum weight must be greater than or equal to 0"
        )
    }

    @Test
    fun `weighing builder with max size throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .maximumCacheSize(5)
                .buildWithWeigher<Any, Any>(OneWeigher(), 0)
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "maximum size can not be combined with weigher"
        )
    }

    @Test
    fun `weighing builder with weigher and max weight properly builds a Store`() {
        val myWeigher = OneWeigher<Any, Any>()

        for (index in 1..100L) {
            val builtCache = Cache.Builder.newBuilder()
            .buildWithWeigher<Any, Any>(myWeigher, index) as RealCache<Any, Any>
            assertThat(builtCache.weigher).isEqualTo(myWeigher)
            assertThat(builtCache.maxWeight).isEqualTo(index)
        }
    }

    @Test
    fun `test evicition when adding items`() {
        val weight = 2
        val myWeigher = ConstantWeigher<Long, Long>(weight)
        val maxWeight = 2 * MAX_SIZE
        val builtCache = Cache.Builder.newBuilder()
        .buildWithWeigher<Long, Long>(myWeigher, maxWeight) as RealCache<Long, Long>

        for (i in 0..(2 * MAX_SIZE)) {
            builtCache.put(i, i)
            assertThat(builtCache.totalWeight).isEqualTo(Math.min((i + 1) * weight, maxWeight))
        }

        assertThat(builtCache.totalWeight).isEqualTo(maxWeight)
    }

    @Test
    fun `test evicition when loading items`() {
        val weight = 2
        val myWeigher = ConstantWeigher<Long, Long>(weight)
        val maxWeight = 2 * MAX_SIZE
        val builtCache = Cache.Builder.newBuilder()
        .buildWithWeigher<Long, Long>(myWeigher, maxWeight) as RealCache<Long, Long>

        for (i in 0..(2 * MAX_SIZE)) {
            builtCache.get(i) { i }
            assertThat(builtCache.totalWeight).isEqualTo(Math.min((i + 1) * weight, maxWeight))
        }
        assertThat(builtCache.totalWeight).isEqualTo(maxWeight)
    }

    /**
    * With an unlimited-size cache with maxWeight of 0, entries weighing 0 should still be cached.
    * Entries with positive weight should not be cached (nor dump existing cache).
    */
    @Test
    fun `test evicition when max weight is 0`() {
        val myWeigher = EvensWeigher()
        val maxWeight = 0L
        val builtCache = Cache.Builder.newBuilder()
        .buildWithWeigher<Int, Int>(myWeigher, maxWeight) as RealCache<Int, Int>
        // 1 won't get cached
        assertThat(builtCache.get(1) { 1 }).isEqualTo(1)
        assertThat(builtCache.get(1)).isNull()
        assertThat(builtCache.totalWeight).isEqualTo(0)
        // 2 will be cached as its weight is 0
        assertThat(builtCache.get(2) { 2 }).isEqualTo(2)
        assertThat(builtCache.get(2)).isNotNull()
        assertThat(builtCache.totalWeight).isEqualTo(0)
        // 4 will be cached as its weight is 0
        assertThat(builtCache.get(4) { 4 }).isEqualTo(4)
        assertThat(builtCache.get(2)).isNotNull()
        assertThat(builtCache.get(4)).isNotNull()
        assertThat(builtCache.totalWeight).isEqualTo(0)
    }

    /**
    * Tests that when a single entry exceeds the caches's max weight, the new entry is immediately
    * evicted and nothing else.
    * */
    @Test
    fun `test cache and evict nothing when caching something heavier than max weight`() {
        val myWeigher = IntValueWeigher()
        val maxWeight = 4L
        val builtCache = Cache.Builder.newBuilder()
        .buildWithWeigher<Int, Int>(myWeigher, maxWeight) as RealCache<Int, Int>

        assertThat(builtCache.get(2) { 2 }).isEqualTo(2)
        assertThat(builtCache.get(2)).isNotNull()
        assertThat(builtCache.totalWeight).isEqualTo(2)
        // cache 3, evict 2
        assertThat(builtCache.get(3) { 3 }).isEqualTo(3)
        assertThat(builtCache.get(2)).isNull()
        assertThat(builtCache.get(3)).isNotNull()
        assertThat(builtCache.totalWeight).isEqualTo(3)
        // doesn't cache 5, doesn't evict anything else
        assertThat(builtCache.get(5) { 5 }).isEqualTo(5)
        assertThat(builtCache.get(3)).isNotNull()
        assertThat(builtCache.totalWeight).isEqualTo(3)
        // cache 1, doesn't evict anything
        assertThat(builtCache.get(1) { 1 }).isEqualTo(1)
        assertThat(builtCache.get(1)).isNotNull()
        assertThat(builtCache.get(3)).isNotNull()
        assertThat(builtCache.totalWeight).isEqualTo(4)

        // cache 4, evict 1 and 3
        assertThat(builtCache.get(4) { 4 }).isEqualTo(4)
        assertThat(builtCache.get(1)).isNull()
        assertThat(builtCache.get(3)).isNull()
        assertThat(builtCache.totalWeight).isEqualTo(4)
    }
}

internal class ConstantWeigher<K : Any, V : Any>(val constantWeight: Int) : Weigher<K, V> {
    override fun weigh(key: K, value: V): Int = constantWeight
}

internal class EvensWeigher : Weigher<Int, Int> {
    override fun weigh(key: Int, value: Int): Int = key % 2
}

internal class IntValueWeigher : Weigher<Int, Int> {
    override fun weigh(key: Int, value: Int): Int = value
}
