@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.drain.internal.DrainRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DrainRegistryTest {
    @Test
    fun registerRejectsDuplicateInvalidAndSameStoreTwice() {
        val registry = DrainRegistry()
        val stores = List(3) { DrainFixture().openStore() }
        try {
            val policy = DrainPolicy()
            val registration = registry.register("users.v1-prod", stores[0], policy)

            assertSame(registration, registry.get("users.v1-prod"))
            assertEquals(listOf(registration), registry.snapshot())
            assertSame(stores[0], registration.store)
            assertSame(policy, registration.policy)
            assertTrue(registration.job.isActive)
            assertNull(registration.derivationState.previousFingerprint)
            assertEquals(0, registration.derivationState.noProgressPasses)
            assertTrue(registration.passMutex.tryLock())
            registration.passMutex.unlock()

            assertFailsWith<IllegalArgumentException> {
                registry.register("users.v1-prod", stores[1], DrainPolicy())
            }
            assertFailsWith<IllegalArgumentException> {
                registry.register("users.v2", stores[0], DrainPolicy())
            }
            listOf("", "users/partial", "a".repeat(65)).forEach { invalidName ->
                assertFailsWith<IllegalArgumentException> {
                    registry.register(invalidName, stores[2], DrainPolicy())
                }
            }
        } finally {
            registry.close()
            stores.forEach(MutationStore<*, *>::close)
        }
    }

    @Test
    fun unregisterRemovesCancelsJobAndIsIdempotent() {
        val registry = DrainRegistry()
        val store = DrainFixture().openStore()
        try {
            val registration = registry.register("users", store, DrainPolicy())

            assertSame(registration, registry.unregister("users"))
            assertTrue(registration.job.isCancelled)
            assertNull(registry.get("users"))
            assertEquals(emptyList(), registry.snapshot())
            assertNull(registry.unregister("users"))
        } finally {
            store.close()
        }
    }

    @Test
    fun closeCancelsAllAndPoisonsRegistry() {
        val registry = DrainRegistry()
        val stores = List(3) { DrainFixture().openStore() }
        try {
            val registrations =
                listOf(
                    registry.register("users", stores[0], DrainPolicy()),
                    registry.register("posts", stores[1], DrainPolicy()),
                )

            val removed = registry.close()

            assertEquals(registrations.toSet(), removed.toSet())
            assertTrue(removed.all { it.job.isCancelled })
            assertTrue(registry.isClosed)
            assertEquals(emptyList(), registry.snapshot())
            assertNull(registry.get("users"))
            assertEquals(emptyList(), registry.close())
            assertFailsWith<IllegalStateException> {
                registry.register("comments", stores[2], DrainPolicy())
            }
            assertFailsWith<IllegalStateException> {
                registry.unregister("unknown")
            }
        } finally {
            stores.forEach(MutationStore<*, *>::close)
        }
    }

    @Test
    fun epochsIncreaseAcrossReRegistration() {
        val registry = DrainRegistry()
        val stores = List(2) { DrainFixture().openStore() }
        try {
            val first = registry.register("users", stores[0], DrainPolicy())
            registry.unregister("users")

            val second = registry.register("users", stores[1], DrainPolicy())

            assertTrue(second.epoch > first.epoch)
        } finally {
            registry.close()
            stores.forEach(MutationStore<*, *>::close)
        }
    }

    @Test
    fun concurrentRegisterSingleWinner() = runTest {
        val registry = DrainRegistry()
        val stores = List(2) { DrainFixture().openStore() }
        try {
            val start = CompletableDeferred<Unit>()
            val results =
                stores
                    .map { store ->
                        async(Dispatchers.Default) {
                            start.await()
                            runCatching {
                                registry.register("users", store, DrainPolicy())
                            }
                        }
                    }
            start.complete(Unit)

            val completed = results.awaitAll()

            assertEquals(1, completed.count(Result<*>::isSuccess))
            assertEquals(1, completed.count { it.exceptionOrNull() is IllegalArgumentException })
            assertEquals(1, registry.snapshot().size)
            assertFalse(registry.isClosed)
        } finally {
            registry.close()
            stores.forEach(MutationStore<*, *>::close)
        }
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
