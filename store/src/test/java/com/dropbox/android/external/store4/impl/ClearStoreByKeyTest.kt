package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.get
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ClearStoreByKeyTest {

    private val testScope = TestCoroutineScope()

    private val key = "key"

    private val networkCalls = AtomicInteger(0)
    private val deleteByKeyCalls = AtomicInteger(0)

    private val diskReader = mock<(String) -> Flow<Int?>>()
    private val diskWriter = mock<suspend (String, Int) -> Unit>()

    @Test
    fun `clear(key) with persister`() = testScope.runBlockingTest {
        whenever(diskReader.invoke(key))
            .thenReturn(flowOf(null)) // no value in disk initially
            .thenReturn(flowOf(1)) // value exists after fetching from network
            .thenReturn(flowOf(null)) // no value after clearing
            .thenReturn(flowOf(1)) // value exists after additional network call
        whenever(diskWriter.invoke(key, 1)).thenReturn(Unit)

        val store = StoreBuilder.fromNonFlow<String, Int>(
            fetcher = { networkCalls.incrementAndGet() }
        ).scope(testScope)
            .persister(
                reader = diskReader,
                writer = diskWriter,
                delete = { deleteByKeyCalls.incrementAndGet() }
            )
            .build()

        // should hit network first time
        store.get(key)
        assertThat(networkCalls.get()).isEqualTo(1)

        // should not hit network
        store.get(key)
        assertThat(networkCalls.get()).isEqualTo(1)

        // clear store entry by key
        store.clear(key)
        assertThat(deleteByKeyCalls.get()).isEqualTo(1)

        // should hit network again
        store.get(key)
        assertThat(networkCalls.toInt()).isEqualTo(2)
    }

    @Test
    fun `clear(key) with in-memory cache and no persister`() = testScope.runBlockingTest {
        val store = StoreBuilder.fromNonFlow<String, Int>(
            fetcher = { networkCalls.incrementAndGet() }
        ).scope(testScope)
            .build()

        // should hit network first time
        store.get(key)
        assertThat(networkCalls.get()).isEqualTo(1)

        // should not hit network
        store.get(key)
        assertThat(networkCalls.get()).isEqualTo(1)

        // clear store entry by key
        store.clear(key)

        // should hit network again
        store.get(key)
        assertThat(networkCalls.toInt()).isEqualTo(2)
    }
}
