package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.WallClock

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class FakeWallClock(var now: Long) : WallClock {
    override fun nowEpochMillis(): Long = now
}

@OptIn(ExperimentalStoreApi::class)
internal fun <K : StoreKey, V : Any> storeWith(
    clock: WallClock? = null,
    bookkeeper: Bookkeeper? = null,
    configure: StoreBuilder<K, V>.() -> Unit,
): Store<K, V> =
    StoreBuilder<K, V>().apply {
        clock?.let { this.wallClock(it) }
        bookkeeper?.let { this.bookkeeper(it) }
        configure()
    }.build()

/**
 * Core's conformance base keeps exercising the actual zero-config persistence implementation.
 * The borrowed SQLDelight compilation re-derives this support seam with a public-API equivalent.
 */
@OptIn(ExperimentalStoreApi::class)
internal fun <K : StoreKey, V : Any> defaultConformanceSourceOfTruth(): SourceOfTruth<K, V> =
    org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth()

/**
 * Test-support shutdown: close, then JOIN the store's engine job so no engine coroutine from this
 * test outlives it on Dispatchers.Default. Cleanup-only — never load-bearing for assertions.
 * The borrowed-suite re-derivation in store6-sqldelight cannot reach core internals and settles
 * with close() alone (documented asymmetry; see BorrowedSuiteSupport.kt).
 */
internal suspend fun Store<*, *>.closeAndSettleForTest() {
    close()
    (this as? org.mobilenativefoundation.store6.core.internal.RealStore<*, *>)?.awaitTerminationForTest()
}
