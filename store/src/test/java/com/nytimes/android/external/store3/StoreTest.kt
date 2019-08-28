package com.nytimes.android.external.store3

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import com.nytimes.android.external.cache3.CacheBuilder
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.StalePolicy
import com.nytimes.android.external.store3.util.NoopPersister
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StoreTest(
        private val storeType: TestStoreType
) {
    private val testScope = TestCoroutineScope()
    private val counter = AtomicInteger(0)
    private val fetcher: Fetcher<String, BarCode> = mock()
    private var persister: Persister<String, BarCode> = mock()
    private val barCode = BarCode("key", "value")

    @Test
    fun testSimple() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
                fetcher = fetcher,
                persister = persister
        ).build(storeType)

        whenever(fetcher.fetch(barCode))
                .thenReturn(NETWORK)

        whenever(persister.read(barCode))
                .thenReturn(null)
                .thenReturn(DISK)

        whenever(persister.write(barCode, NETWORK))
                .thenReturn(true)

        var value = simpleStore.get(barCode)

        assertThat(value).isEqualTo(DISK)
        value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(DISK)
        verify(fetcher, times(1)).fetch(barCode)
    }

    @Test
    fun testDoubleTap() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
                fetcher = fetcher,
                persister = persister
        ).build(storeType)
        whenever(fetcher.fetch(barCode))
                .thenAnswer {
                    if (counter.incrementAndGet() == 1) {
                        NETWORK
                    } else {
                        throw RuntimeException("Yo Dawg your inflight is broken")
                    }
                }

        whenever(persister.read(barCode))
                .thenReturn(null)
                .thenReturn(DISK)

        whenever(persister.write(barCode, NETWORK))
                .thenReturn(true)


        val deferred = async { simpleStore.get(barCode) }
        simpleStore.get(barCode)
        deferred.await()

        verify(fetcher, times(1)).fetch(barCode)
    }

    @Test
    fun testSubclass() = testScope.runBlockingTest {

        val simpleStore = TestStoreBuilder.from(
                fetcher = fetcher,
                persister = persister
        ).build(storeType)

        simpleStore.clear(barCode)

        whenever(fetcher.fetch(barCode))
                .thenReturn(NETWORK)

        whenever(persister.read(barCode))
                .thenReturn(null)
                .thenReturn(DISK)
        whenever(persister.write(barCode, NETWORK)).thenReturn(true)

            var value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(DISK)
        value = simpleStore.get(barCode)
        assertThat(value).isEqualTo(DISK)
        verify(fetcher, times(1)).fetch(barCode)
    }

    @Test
    fun testNoopAndDefault() = testScope.runBlockingTest {
        val persister = spy(NoopPersister.create<String, BarCode>())
        val simpleStore = TestStoreBuilder.from(
                fetcher = fetcher,
                persister = persister,
                cached = true
        ).build(storeType)

        whenever(fetcher.fetch(barCode))
                .thenReturn(NETWORK)

        var value = simpleStore.get(barCode)
        verify(fetcher, times(1)).fetch(barCode)
        verify(persister, times(1)).write(barCode, NETWORK)
        verify(persister, times(2)).read(barCode)
        assertThat(value).isEqualTo(NETWORK)


        value = simpleStore.get(barCode)
        verify(persister, times(2)).read(barCode)
        verify(persister, times(1)).write(barCode, NETWORK)
        verify(fetcher, times(1)).fetch(barCode)

        assertThat(value).isEqualTo(NETWORK)
    }

    @Test
    fun testEquivalence() = testScope.runBlockingTest {
        val cache = CacheBuilder.newBuilder()
                .maximumSize(1)
                .expireAfterAccess(java.lang.Long.MAX_VALUE, TimeUnit.SECONDS)
                .build<BarCode, String>()

        cache.put(barCode, MEMORY)
        var value = cache.getIfPresent(barCode)
        assertThat(value).isEqualTo(MEMORY)

        value = cache.getIfPresent(BarCode(barCode.type, barCode.key))
        assertThat(value).isEqualTo(MEMORY)
    }

    @Test
    fun testFreshUsesOnlyNetwork() = testScope.runBlockingTest {
        val simpleStore = TestStoreBuilder.from(
                fetcher = fetcher,
                persister = persister,
                persisterStalePolicy = StalePolicy.NETWORK_BEFORE_STALE
        ).build(storeType)

        whenever(fetcher.fetch(barCode)) doThrow RuntimeException(ERROR)

        whenever(persister.read(barCode)) doReturn DISK

        try {
            simpleStore.fresh(barCode)
            fail("Exception not thrown!")
        } catch (e: Exception) {
            assertThat(e.message).isEqualTo(ERROR)
        }

        verify(fetcher, times(1)).fetch(barCode)
        verify(persister, never()).read(any())
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
