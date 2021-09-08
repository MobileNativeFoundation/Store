package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.fresh
import com.dropbox.android.external.store4.get
import com.google.common.cache.CacheBuilder
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.ExperimentalTime

@ExperimentalTime
@RunWith(Parameterized::class)
class StoreTest(
    private val storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private val counter = AtomicInteger(0)
    private val fetcher: Fetcher<Pair<String, String>, String> = mock()
    private var reader: (Pair<String, String>) -> String = mock()
    private var writer: (Pair<String, String>, String) -> Boolean = mock()
    private val barCode = "key" to "value"

    @Test
    fun testSimple() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
            scope = testScope,
            fetcher = fetcher,
            reader = reader,
            writer = writer
        ).build(storeType)

        whenever(fetcher.invoke(barCode))
            .thenReturn(flowOf(FetcherResult.Data(NETWORK)))

        whenever(reader(barCode))
            .thenReturn(null)
            .thenReturn(DISK)

        whenever(writer(barCode, NETWORK))
            .thenReturn(true)

        var value = simpleStore.get(barCode)

        assertThat(value).isEqualTo(DISK)
        value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(DISK)
        verify(fetcher, times(1)).invoke(barCode)
    }

    @Test
    fun testDoubleTap() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
            scope = testScope,
            fetcher = fetcher,
            reader = reader,
            writer = writer
        ).build(storeType)
        whenever(fetcher.invoke(barCode))
            .thenAnswer {
                if (counter.incrementAndGet() == 1) {
                    flowOf(FetcherResult.Data(NETWORK))
                } else {
                    flowOf(FetcherResult.Error.Message("Yo Dawg your inflight is broken"))
                }
            }

        whenever(reader(barCode))
            .thenReturn(null)
            .thenReturn(DISK)

        whenever(writer(barCode, NETWORK))
            .thenReturn(true)

        val deferred = async { simpleStore.get(barCode) }
        simpleStore.get(barCode)
        deferred.await()

        verify(fetcher, times(1)).invoke(barCode)
    }

    @Test
    fun testSubclass() = testScope.runBlockingTest {

        val simpleStore = TestStoreBuilder.from(
            scope = testScope,
            fetcher = fetcher,
            reader = reader,
            writer = writer
        ).build(storeType)

        simpleStore.clear(barCode)

        whenever(fetcher.invoke(barCode))
            .thenReturn(flowOf(FetcherResult.Data(NETWORK)))

        whenever(reader(barCode))
            .thenReturn(null)
            .thenReturn(DISK)
        whenever(writer(barCode, NETWORK)).thenReturn(true)

        var value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(DISK)
        value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(DISK)
        verify(fetcher, times(1)).invoke(barCode)
    }

    @Test
    fun testEquivalence() = testScope.runBlockingTest {
        val cache = CacheBuilder.newBuilder()
            .maximumSize(1)
            .expireAfterAccess(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            .build<Pair<String, String>, String>()

        cache.put(barCode, MEMORY)
        var value = cache.getIfPresent(barCode)
        assertThat(value).isEqualTo(MEMORY)

        value = cache.getIfPresent(barCode.first to barCode.second)
        assertThat(value).isEqualTo(MEMORY)
    }

    @Test
    fun testFreshUsesOnlyNetwork() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
            scope = testScope,
            fetcher = fetcher,
            reader = reader,
            writer = writer
        ).build(storeType)

        whenever(fetcher.invoke(barCode)) doReturn
            flowOf(FetcherResult.Error.Message(ERROR))

        whenever(reader(barCode)) doReturn DISK

        try {
            simpleStore.fresh(barCode)
            fail("Exception not thrown!")
        } catch (e: Exception) {
            assertThat(e.message).isEqualTo(ERROR)
        }

        verify(fetcher, times(1)).invoke(barCode)
        verify(reader, never()).invoke(any())
    }

    @Test
    fun `GIVEN no new data WHEN get THEN returns disk data`() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
            scope = testScope,
            fetcher = fetcher,
            reader = reader,
            writer = writer
        ).build(storeType)

        whenever(fetcher.invoke(barCode)) doReturn
            flowOf()

        whenever(reader(barCode)) doReturn DISK

        val value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(DISK)
    }

    @Test
    fun `GIVEN no new data WHEN fresh THEN returns disk data`() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
            scope = testScope,
            fetcher = fetcher,
            reader = reader,
            writer = writer
        ).build(storeType)

        whenever(fetcher.invoke(barCode)) doReturn
            flowOf()

        whenever(reader(barCode)) doReturn DISK

        val value = simpleStore.fresh(barCode)
        assertThat(value).isEqualTo(DISK)
    }

    companion object {
        private const val DISK = "disk"
        private const val NETWORK = "fresh"
        private const val MEMORY = "memory"
        private const val ERROR = "error!!"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = TestStoreType.values()
    }
}
