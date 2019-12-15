package com.dropbox.android.external.store3.base.impl

import com.dropbox.android.external.store4.MemoryPolicy

import org.junit.Test

import java.util.concurrent.TimeUnit

import com.google.common.truth.Truth.assertThat

class MemoryPolicyBuilderTest {

    @Test
    fun testBuildExpireAfterWriteMemoryPolicy() {
        val policy = MemoryPolicy.builder()
                .setExpireAfterWrite(4L)
                .build()

        assertThat(policy.expireAfterWrite).isEqualTo(4L)
        assertThat(policy.expireAfterTimeUnit).isEqualTo(TimeUnit.SECONDS)
        assertThat(policy.isDefaultWritePolicy).isFalse()
        assertThat(policy.expireAfterAccess).isEqualTo(MemoryPolicy.DEFAULT_POLICY)
    }

    @Test
    fun testBuildExpireAfterAccessMemoryPolicy() {
        val policy = MemoryPolicy.builder()
                .setExpireAfterAccess(4L)
                .build()

        assertThat(policy.expireAfterAccess).isEqualTo(4L)
        assertThat(policy.expireAfterTimeUnit).isEqualTo(TimeUnit.SECONDS)
        assertThat(policy.isDefaultWritePolicy).isTrue()
        assertThat(policy.expireAfterWrite).isEqualTo(MemoryPolicy.DEFAULT_POLICY)
    }

    @Test(expected = IllegalStateException::class)
    fun testCannotSetBothExpirationPolicies() {
        MemoryPolicy.builder()
                .setExpireAfterAccess(4L)
                .setExpireAfterWrite(4L)
                .build()
    }

    @Test
    fun testBuilderSetsExpireAfterTimeUnit() {
        val policy = MemoryPolicy.builder()
                .setExpireAfterTimeUnit(TimeUnit.MINUTES)
                .build()

        assertThat(policy.expireAfterTimeUnit).isEqualTo(TimeUnit.MINUTES)
    }

    @Test
    fun testBuilderSetsMemorySize() {
        val policy = MemoryPolicy.builder()
                .setMemorySize(10L)
                .build()

        assertThat(policy.maxSize).isEqualTo(10L)
    }

    @Test
    fun testDefaultMemorySizeIfNotSet() {
        val policy = MemoryPolicy.builder()
                .build()

        assertThat(policy.maxSize).isEqualTo(1L)
    }
}
