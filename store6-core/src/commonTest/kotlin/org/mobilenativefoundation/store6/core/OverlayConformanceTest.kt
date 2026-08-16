package org.mobilenativefoundation.store6.core

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class OverlayConformanceTest {
    private class TestOverlay(
        var transform: (String?) -> String?,
        val signals: MutableSharedFlow<StoreKey> = MutableSharedFlow(replay = 1),
    ) : Overlay<TestKey, String> {
        // Transform mutation precedes emit, and emit -> collect supplies ordering. Replay tolerates
        // the projection writer's subscription racing the first signal.
        override fun apply(
            key: TestKey,
            base: String?,
        ): String? = transform(base)

        override val changes: Flow<StoreKey>
            get() = signals
    }

    private suspend fun ReceiveTurbine<StoreResult<String>>.awaitDataValue(
        expected: String,
    ): StoreResult.Data<String> {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data<String> && item.value == expected) return item
        }
    }

    private suspend fun recordUntil(
        store: Store<TestKey, String>,
        key: TestKey,
        script: suspend () -> Unit,
        terminal: String,
    ): List<String> {
        val recorded = mutableListOf<String>()
        var scriptRan = false
        store.stream(key).test {
            while (true) {
                when (val item = awaitItem()) {
                    is StoreResult.Loading -> recorded += "loading"
                    is StoreResult.Data<String> -> {
                        recorded +=
                            "data(${item.value},${item.origin},stale=${item.isStale})"
                        if (item.value == terminal) {
                            cancelAndIgnoreRemainingEvents()
                            return@test
                        }
                        if (!scriptRan) {
                            scriptRan = true
                            script()
                        }
                    }
                    is StoreResult.Revalidated -> recorded += "revalidated"
                    is StoreResult.Error -> recorded += "error"
                }
            }
        }
        return recorded
    }

    @Test
    fun identityDefault_emissionSequenceUnchanged() = runTest {
        var plainFetches = 0
        var projectedFetches = 0
        val plain = store<TestKey, String> { fetcher { "v${++plainFetches}" } }
        val passThrough = store<TestKey, String> {
            fetcher { "v${++projectedFetches}" }
            overlay(
                object : Overlay<TestKey, String> {
                    override fun apply(
                        key: TestKey,
                        base: String?,
                    ): String? = base

                    override val changes: Flow<StoreKey> = emptyFlow()
                },
            )
        }
        val key = TestKey("1")

        try {
            val plainSequence =
                recordUntil(
                    store = plain,
                    key = key,
                    script = { plain.invalidate(key) },
                    terminal = "v2",
                )
            val projectedSequence =
                recordUntil(
                    store = passThrough,
                    key = key,
                    script = { passThrough.invalidate(key) },
                    terminal = "v2",
                )

            assertEquals(plainSequence, projectedSequence)
        } finally {
            plain.close()
            passThrough.close()
        }
    }

    @Test
    fun modifyingOverlay_stampsOverlayOrigin() = runTest {
        val store = store<TestKey, String> {
            fetcher { "v" }
            overlay(TestOverlay({ it?.uppercase() }))
        }

        try {
            store.stream(TestKey("1")).test {
                val data = awaitDataValue("V")
                assertEquals(Origin.OVERLAY, data.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun changeSignal_reprojectsForActiveCollectors() = runTest {
        val overlay = TestOverlay({ it })
        val store = store<TestKey, String> {
            fetcher { "v" }
            overlay(overlay)
        }

        try {
            store.stream(TestKey("1")).test {
                awaitDataValue("v")
                overlay.transform = { base -> base + "+pending" }
                overlay.signals.emit(TestKey("1"))
                val projected = awaitDataValue("v+pending")
                assertEquals(Origin.OVERLAY, projected.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun absentBase_overlayProjectionServesCreate() = runTest {
        val store = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(TestOverlay({ it ?: "pending-create" }))
        }

        try {
            store.stream(TestKey("1")).test {
                val data = awaitDataValue("pending-create")
                assertEquals(Origin.OVERLAY, data.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun nullProjection_overResident_emitsAbsentTransition() = runTest {
        val overlay = TestOverlay({ it })
        val store = store<TestKey, String> {
            fetcher { "v" }
            overlay(overlay)
        }

        try {
            store.stream(TestKey("1")).test {
                awaitDataValue("v")
                overlay.transform = { null }
                overlay.signals.emit(TestKey("1"))
                assertIs<StoreResult.Loading>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun retireAfterConfirmedCommit_neverReemitsOldBase() = runTest {
        val overlay = TestOverlay({ base -> base?.plus("+op") })
        val store = store<TestKey, String> {
            fetcher { "v1" }
            overlay(overlay)
        }
        val key = TestKey("1")

        try {
            store.stream(key).test {
                awaitDataValue("v1+op")
                cancelAndIgnoreRemainingEvents()
            }
            store.runtime()!!.writeHandle.apply(key, "v2")
            overlay.transform = { it }
            overlay.signals.emit(TestKey("1"))

            store.stream(key).test {
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String>) {
                        assertTrue(item.value != "v1" && item.value != "v1+op")
                        if (item.value == "v2") {
                            assertTrue(item.origin == Origin.SOT || item.origin == Origin.MEMORY)
                            cancelAndIgnoreRemainingEvents()
                            break
                        }
                    }
                }
            }
        } finally {
            store.close()
        }
    }
}
