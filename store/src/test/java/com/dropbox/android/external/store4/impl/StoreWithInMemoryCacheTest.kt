package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.cache3.RemovalCause
import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.get
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.time.ExperimentalTime
import kotlin.time.minutes


@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class StoreWithInMemoryCacheTest {

    @Test
    fun `store requests can complete when its in-memory cache (with access expiry) is at the maximum size`() {
        val store = StoreBuilder
            .from(Fetcher.of { _: Int -> "result" })
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .setExpireAfterAccess(10.minutes)
                    .setMaxSize(1)
                    .build()
            )
            .build()

        runBlocking {
            store.get(0)
            store.get(0)
            store.get(1)
            store.get(2)
        }
    }

    @Test
    fun `store requests with removal listener when its in-memory cache is at the maximum size`() {
        val listenerMock = mock<(Int, String, RemovalCause) -> Unit>()
        val scope = CoroutineScope(Job())

        val store = StoreBuilder
            .from(Fetcher.of { key: Int -> "result_$key" })
            .scope(scope)
            .cachePolicy(
                MemoryPolicy
                    .builder<Int, String>()
                    .setMaxSize(1)
                    .setRemovalListener(listenerMock)
                    .build()
            )
            .build()

        runBlocking {
            store.get(0)
            store.get(1)
            store.get(2)

            verify(listenerMock).invoke(0, "result_0", RemovalCause.SIZE)
            verify(listenerMock).invoke(1, "result_1", RemovalCause.SIZE)
        }
    }

    @Test
    fun `store requests with removal listener when scope is cancel`() {
        val listenerMock = mock<(Int, String, RemovalCause) -> Unit>()
        val scope = CoroutineScope(Job())

        val store = StoreBuilder
            .from(Fetcher.of { key: Int -> "result_$key" })
            .scope(scope)
            .cachePolicy(
                MemoryPolicy
                    .builder<Int, String>()
                    .setMaxSize(1)
                    .setRemovalListener(listenerMock)
                    .build()
            )
            .build()

        runBlocking {
            store.get(0)
            store.get(1)

            scope.cancel()
            // TODO We must call store.get(2) to verify that the listener is not call
            // but with the cancelled scope the methode get throw the JobCancellationException

            verify(listenerMock).invoke(0, "result_0", RemovalCause.SIZE)
            verify(listenerMock, never()).invoke(1, "result_1", RemovalCause.SIZE)
        }
    }
}