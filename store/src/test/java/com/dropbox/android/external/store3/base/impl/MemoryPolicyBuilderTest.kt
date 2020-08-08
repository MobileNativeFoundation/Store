package com.dropbox.android.external.store3.base.impl

import com.dropbox.android.external.store4.MemoryPolicy
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
}
