@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.realtime

import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeStore
import org.mobilenativefoundation.store6.testing.FakeStoreInteraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class RealtimeBindingLifecycleTest {
    @Test
    fun consume_appliesMessagesInOrder_thenCompletes() = runTest {
        val store = store<RealtimeTestKey, String> { fetcher { "fetched" } }
        val handle = RecordingWriteHandle()
        val binding = RealtimeBinding(store, handle)
        val key = RealtimeTestKey("consume")

        try {
            binding.consume(
                flowOf(
                    RealtimeMessage.Upsert(key, "a", etag = "e1"),
                    RealtimeMessage.Changed(key),
                    RealtimeMessage.Unchanged(key, etag = "e2"),
                ),
            )
            assertEquals(listOf("apply", "confirmFresh", "markStale", "confirmFresh"), handle.events)
        } finally {
            store.close()
        }
    }

    @Test
    fun consume_propagatesError_andStops() = runTest {
        val store = store<RealtimeTestKey, String> { fetcher { "fetched" } }
        val handle =
            object : StoreWriteHandle<RealtimeTestKey, String> {
                val events = mutableListOf<String>()

                override suspend fun apply(
                    key: RealtimeTestKey,
                    value: String,
                ) {
                    events += "apply:$value"
                    if (value == "boom") error("upsert failed")
                }

                override suspend fun markStale(key: RealtimeTestKey) {
                    events += "markStale"
                }

                override suspend fun confirmFresh(
                    key: RealtimeTestKey,
                    etag: String?,
                ) {
                    events += "confirmFresh"
                }
            }
        val binding = RealtimeBinding(store, handle)
        val key = RealtimeTestKey("fail")

        try {
            val failure =
                assertFailsWith<IllegalStateException> {
                    binding.consume(
                        flowOf(
                            RealtimeMessage.Upsert(key, "ok", etag = null),
                            RealtimeMessage.Upsert(key, "boom", etag = null),
                            RealtimeMessage.Changed(key),
                        ),
                    )
                }
            assertEquals("upsert failed", failure.message)
            assertEquals(listOf("apply:ok", "confirmFresh", "apply:boom"), handle.events)
        } finally {
            store.close()
        }
    }

    @Test
    fun concurrentApply_serializesUpsertPair() = runTest {
        val store = store<RealtimeTestKey, String> { fetcher { "fetched" } }
        val applyStarted = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val handle =
            object : StoreWriteHandle<RealtimeTestKey, String> {
                val events = mutableListOf<String>()
                private var applies = 0

                override suspend fun apply(
                    key: RealtimeTestKey,
                    value: String,
                ) {
                    events += "apply"
                    applies += 1
                    if (applies == 1) {
                        applyStarted.complete(Unit)
                        releaseApply.await()
                    }
                }

                override suspend fun markStale(key: RealtimeTestKey) = Unit

                override suspend fun confirmFresh(
                    key: RealtimeTestKey,
                    etag: String?,
                ) {
                    events += "confirmFresh"
                }
            }
        val binding = RealtimeBinding(store, handle)
        val key = RealtimeTestKey("serial")

        try {
            val first = async { binding.apply(RealtimeMessage.Upsert(key, "a", etag = "e1")) }
            applyStarted.await()
            val second = async { binding.apply(RealtimeMessage.Upsert(key, "b", etag = "e2")) }
            releaseApply.complete(Unit)
            first.await()
            second.await()
            assertEquals(listOf("apply", "confirmFresh", "apply", "confirmFresh"), handle.events)
        } finally {
            store.close()
        }
    }

    @Test
    fun apply_onClosedStore_throws() = runTest {
        val store = store<RealtimeTestKey, String> { fetcher { "fetched" } }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("closed")
        store.close()

        val failure =
            assertFailsWith<IllegalStateException> {
                binding.apply(RealtimeMessage.Changed(key))
            }
        assertEquals("Store is closed.", failure.message)
    }

    @Test
    fun consume_onClosedStore_throws() = runTest {
        val store = store<RealtimeTestKey, String> { fetcher { "fetched" } }
        val binding = realtimeBinding(store)
        store.close()

        val failure =
            assertFailsWith<IllegalStateException> {
                binding.consume(flow { emit(RealtimeMessage.ChangedAll) })
            }
        assertEquals("Store is closed.", failure.message)
    }

    @Test
    fun realtimeBinding_rejectsFakeStore_withPinnedMessage() = runTest {
        val store = FakeStore<RealtimeTestKey, String>()
        try {
            val failure = assertFailsWith<IllegalArgumentException> { realtimeBinding(store) }
            assertEquals(ADOPTING_BINDING_UNAVAILABLE, failure.message)
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidatingBinding_acceptsFakeStore() = runTest {
        val store = FakeStore<RealtimeTestKey, String>()
        try {
            invalidatingRealtimeBinding(store).apply(RealtimeMessage.ChangedAll)
            assertEquals(1, store.interactions.filterIsInstance<FakeStoreInteraction.InvalidateAll>().size)
        } finally {
            store.close()
        }
    }
}

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
