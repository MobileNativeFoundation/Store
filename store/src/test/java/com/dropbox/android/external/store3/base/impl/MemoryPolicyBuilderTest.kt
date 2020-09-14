package com.dropbox.android.external.store3.base.impl

import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.OneWeigher
import com.dropbox.android.external.store4.Weigher
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
class MemoryPolicyBuilderTest {

    @Test
    fun testBuildExpireAfterWriteMemoryPolicy() {
        val policy = MemoryPolicy.builder<Any, Any>()
                .setExpireAfterWrite(4.seconds)
                .build()

        assertThat(policy.expireAfterWrite).isEqualTo(4.seconds)
        assertThat(policy.isDefaultWritePolicy).isFalse()
        assertThat(policy.expireAfterAccess).isEqualTo(MemoryPolicy.DEFAULT_DURATION_POLICY)
    }

    @Test
    fun testBuildExpireAfterAccessMemoryPolicy() {
        val policy = MemoryPolicy.builder<Any, Any>()
                .setExpireAfterAccess(4.seconds)
                .build()

        assertThat(policy.expireAfterAccess).isEqualTo(4.seconds)
        assertThat(policy.isDefaultWritePolicy).isTrue()
        assertThat(policy.expireAfterWrite).isEqualTo(MemoryPolicy.DEFAULT_DURATION_POLICY)
    }

    @Test(expected = IllegalStateException::class)
    fun testCannotSetBothExpirationPolicies() {
        MemoryPolicy.builder<Any, Any>()
                .setExpireAfterAccess(4.seconds)
                .setExpireAfterWrite(4.seconds)
                .build()
    }

    @Test
    fun testBuilderSetsMemorySize() {
        val policy = MemoryPolicy.builder<Any, Any>()
                .setMaxSize(10L)
                .build()

        assertThat(policy.hasMaxSize).isEqualTo(true)
        assertThat(policy.maxSize).isEqualTo(10L)
    }

    @Test
    fun testDefaultMemorySizeIfNotSet() {
        val policy = MemoryPolicy.builder<Any, Any>().build()

        assertThat(policy.hasMaxSize).isEqualTo(false)
        assertThat(policy.maxSize).isEqualTo(MemoryPolicy.DEFAULT_SIZE_POLICY)
    }

    @Test
    fun testBuilderSetsMemoryWeight() {
        val weigher = Weigher<Any, Any> { key , value ->
            key.hashCode() + value.hashCode()
        }
        val policy = MemoryPolicy.builder<Any, Any>()
            .setWeigherAndMaxWeight(weigher, 10L)
            .build()

        assertThat(policy.hasMaxWeight).isEqualTo(true)
        assertThat(policy.maxWeight).isEqualTo(10L)
        assertThat(policy.weigher).isSameInstanceAs(weigher)
    }

    @Test
    fun testDefaultMemoryWeightIfNotSet() {
        val policy = MemoryPolicy.builder<Any, Any>().build()

        assertThat(policy.hasMaxWeight).isEqualTo(false)
        assertThat(policy.maxWeight).isEqualTo(MemoryPolicy.DEFAULT_SIZE_POLICY)
        assertThat(policy.weigher).isSameInstanceAs(OneWeigher)
    }

    @Test(expected = java.lang.IllegalStateException::class)
    fun testMaxWeightCannotBeSetAfterMaxSize() {
        val policy = MemoryPolicy.builder<Any, Any>()
            .setMaxSize(10L)
            .setWeigherAndMaxWeight(OneWeigher, 10L)
            .build()
    }

    @Test(expected = java.lang.IllegalStateException::class)
    fun testMaxSizeCannotBeSetAfterMaxWeight() {
        val policy = MemoryPolicy.builder<Any, Any>()
            .setWeigherAndMaxWeight(OneWeigher, 10L)
            .setMaxSize(10L)
            .build()
    }
}
