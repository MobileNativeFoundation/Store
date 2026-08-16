package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * Simulates restart by constructing a fresh Store around the same Bookkeeper and SourceOfTruth.
 * This proves store-instance-independent durable facts, not on-disk durability, which the
 * persistence adapter modules cover.
 */
@OptIn(ExperimentalStoreApi::class)
abstract class StoreDurableMaintenanceConformance {
    protected abstract fun <K : StoreKey, V : Any> createSourceOfTruth(): SourceOfTruth<K, V>

    @Test
    fun invalidate_markIsObservedByFreshStoreUsingSharedCollaborators() = runTest {
        var calls = 0
        val sharedBookkeeper = InMemoryBookkeeper()
        val sharedSot = createSourceOfTruth<TestKey, String>()
        val clock = FakeWallClock(now = 100L)
        fun buildStore(): Store<TestKey, String> =
            restartedStore(sharedBookkeeper, sharedSot, clock) { "v${++calls}" }

        val first = buildStore()
        try {
            assertEquals("v1", first.get(TestKey("1")))
            first.invalidate(TestKey("1"))
        } finally {
            first.close()
        }

        val second = buildStore()
        try {
            assertEquals("v2", second.get(TestKey("1"), Freshness.MaxAge(1.hours)))
            assertEquals(2, calls)
        } finally {
            second.close()
        }
    }

    @Test
    fun invalidateNamespace_watermarkIsObservedForKeyUnseenByFreshStore() = runTest {
        var calls = 0
        val sharedBookkeeper = InMemoryBookkeeper()
        val sharedSot = createSourceOfTruth<NamespacedTestKey, String>()
        val clock = FakeWallClock(now = 100L)
        fun buildStore(): Store<NamespacedTestKey, String> =
            restartedStore(sharedBookkeeper, sharedSot, clock) { "v${++calls}" }
        val keyA1 = NamespacedTestKey("a", "1")
        val keyA2 = NamespacedTestKey("a", "2")

        val first = buildStore()
        try {
            assertEquals("v1", first.get(keyA1))
            assertEquals("v2", first.get(keyA2))
            first.invalidateNamespace(StoreNamespace("a"))
        } finally {
            first.close()
        }

        val second = buildStore()
        try {
            assertEquals("v3", second.get(keyA2, Freshness.MaxAge(1.hours)))
            assertEquals(3, calls)
        } finally {
            second.close()
        }
    }

    @Test
    fun invalidateAll_globalWatermarkIsObservedAcrossNamespacesByFreshStore() = runTest {
        var calls = 0
        val sharedBookkeeper = InMemoryBookkeeper()
        val sharedSot = createSourceOfTruth<NamespacedTestKey, String>()
        val clock = FakeWallClock(now = 100L)
        fun buildStore(): Store<NamespacedTestKey, String> =
            restartedStore(sharedBookkeeper, sharedSot, clock) { "v${++calls}" }
        val keyA = NamespacedTestKey("a", "1")
        val keyB = NamespacedTestKey("b", "1")

        val first = buildStore()
        try {
            assertEquals("v1", first.get(keyA))
            assertEquals("v2", first.get(keyB))
            first.invalidateAll()
        } finally {
            first.close()
        }

        val second = buildStore()
        try {
            assertEquals("v3", second.get(keyA, Freshness.MaxAge(1.hours)))
            assertEquals("v4", second.get(keyB, Freshness.MaxAge(1.hours)))
            assertEquals(4, calls)
        } finally {
            second.close()
        }
    }

    @Test
    fun freshSidecarWithoutInvalidation_servesHydratedValueAfterFreshStoreStarts() = runTest {
        var calls = 0
        val sharedBookkeeper = InMemoryBookkeeper()
        val sharedSot = createSourceOfTruth<TestKey, String>()
        val clock = FakeWallClock(now = 100L)
        fun buildStore(): Store<TestKey, String> =
            restartedStore(sharedBookkeeper, sharedSot, clock) { "v${++calls}" }

        val first = buildStore()
        try {
            assertEquals("v1", first.get(TestKey("1")))
        } finally {
            first.close()
        }

        val second = buildStore()
        try {
            assertEquals("v1", second.get(TestKey("1"), Freshness.MaxAge(1.hours)))
            assertEquals(1, calls)
        } finally {
            second.close()
        }
    }

    private fun <K : StoreKey, V : Any> restartedStore(
        sharedBookkeeper: Bookkeeper,
        sharedSot: SourceOfTruth<K, V>,
        clock: FakeWallClock,
        fetch: suspend (K) -> V,
    ): Store<K, V> =
        storeWith(clock = clock, bookkeeper = sharedBookkeeper) {
            fetcher(fetch)
            persistence(sharedSot)
        }
}

@OptIn(ExperimentalStoreApi::class)
class StoreDurableMaintenanceConformanceTest : StoreDurableMaintenanceConformance() {
    override fun <K : StoreKey, V : Any> createSourceOfTruth(): SourceOfTruth<K, V> =
        InMemorySourceOfTruth()
}
