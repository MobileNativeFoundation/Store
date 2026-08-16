package org.mobilenativefoundation.store6.core

import app.cash.turbine.testIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class SourceOfTruthHydrationRaceTest {
    private val key = TestKey("hydration-race")

    @Test
    fun getHydrationRacingClear_neverResurrectsAfterClearReturns() = runTest {
        val sourceOfTruth = ClearRacingHydrationSourceOfTruth()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }

        try {
            val hydration =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key, Freshness.LocalOnly)
                }
            sourceOfTruth.readerStarted.await()
            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.clear(key)
                }
            runCurrent()

            sourceOfTruth.releaseReader.complete(Unit)
            assertEquals("snapshot", hydration.await())
            clear.await()

            val missing =
                assertFailsWith<StoreException> {
                    store.get(key, Freshness.LocalOnly)
                }
            assertIs<StoreError.Missing>(missing.error)
        } finally {
            sourceOfTruth.releaseReader.complete(Unit)
            store.close()
        }
    }

    @Test
    fun externalAbsentObservedDuringHydration_neverResurrectsSnapshot() = runTest {
        val sourceOfTruth = ReactiveHydrationSourceOfTruth()
        val store = localOnlyStore(sourceOfTruth)

        try {
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertIs<StoreError.Missing>(
                    assertIs<StoreResult.Error>(observer.awaitItem()).error,
                )
                sourceOfTruth.sharedReaderStarted.await()
                runCurrent()
                sourceOfTruth.prepareGatedDirectSnapshot("snapshot")

                val hydration =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { store.get(key, Freshness.LocalOnly) }
                    }
                sourceOfTruth.directHydrationStarted.await()

                sourceOfTruth.publish("intermediate")
                assertEquals(
                    "intermediate",
                    assertIs<StoreResult.Data<String>>(observer.awaitItem()).value,
                )
                sourceOfTruth.publish(null)
                var sawAbsent = false
                while (!sawAbsent) {
                    when (val item = observer.awaitItem()) {
                        is StoreResult.Loading -> Unit
                        is StoreResult.Error -> {
                            assertIs<StoreError.Missing>(item.error)
                            sawAbsent = true
                        }
                        is StoreResult.Data -> error("Absent transition emitted ${item.value}.")
                        is StoreResult.Revalidated -> error("005 must not emit Revalidated.")
                    }
                }
                runCurrent()

                sourceOfTruth.releaseDirectHydration.complete(Unit)
                val failure = assertIs<StoreException>(hydration.await().exceptionOrNull())
                assertIs<StoreError.Missing>(failure.error)
                observer.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseDirectHydration.complete(Unit)
            store.close()
        }
    }

    @Test
    fun get_localOnly_directAbsentButReactiveRowWins_usesLiveResidence() = runTest {
        val sourceOfTruth = ReactiveHydrationSourceOfTruth()
        val store = localOnlyStore(sourceOfTruth)

        try {
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertIs<StoreError.Missing>(
                    assertIs<StoreResult.Error>(observer.awaitItem()).error,
                )
                sourceOfTruth.sharedReaderStarted.await()
                runCurrent()
                sourceOfTruth.prepareGatedDirectSnapshot(null)

                val hydration =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        store.get(key, Freshness.LocalOnly)
                    }
                sourceOfTruth.directHydrationStarted.await()

                sourceOfTruth.publish("live")
                assertEquals("live", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                runCurrent()

                sourceOfTruth.releaseDirectHydration.complete(Unit)
                assertEquals("live", hydration.await())
                observer.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseDirectHydration.complete(Unit)
            store.close()
        }
    }

    private fun localOnlyStore(sourceOfTruth: SourceOfTruth<TestKey, String>): Store<TestKey, String> =
        store {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }

    private class ClearRacingHydrationSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("snapshot")
        private var gateFirstReader = true
        val readerStarted = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            if (!gateFirstReader) return rows
            gateFirstReader = false
            val snapshot = rows.value
            return flow {
                readerStarted.complete(Unit)
                releaseReader.await()
                emit(snapshot)
                awaitCancellation()
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class ReactiveHydrationSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows =
            MutableSharedFlow<String?>(
                replay = 1,
                extraBufferCapacity = 8,
            ).also { rows -> check(rows.tryEmit(null)) }
        private var readerCalls = 0
        private var nextDirectSnapshot: String? = null
        private var directSnapshotPrepared = false

        val sharedReaderStarted = CompletableDeferred<Unit>()
        var directHydrationStarted = CompletableDeferred<Unit>()
            private set
        var releaseDirectHydration = CompletableDeferred<Unit>()
            private set

        fun prepareGatedDirectSnapshot(snapshot: String?) {
            check(readerCalls == 2) { "shared reader must be active before direct hydration" }
            check(!directSnapshotPrepared) { "a direct hydration is already prepared" }
            nextDirectSnapshot = snapshot
            directSnapshotPrepared = true
            directHydrationStarted = CompletableDeferred()
            releaseDirectHydration = CompletableDeferred()
        }

        suspend fun publish(value: String?) {
            liveRows.emit(value)
        }

        override fun reader(key: TestKey): Flow<String?> =
            when (++readerCalls) {
                1 -> flow { emit(null) }
                2 ->
                    flow {
                        sharedReaderStarted.complete(Unit)
                        emitAll(liveRows)
                    }

                else -> {
                    check(directSnapshotPrepared) { "unexpected reader invocation $readerCalls" }
                    directSnapshotPrepared = false
                    val snapshot = nextDirectSnapshot
                    flow {
                        directHydrationStarted.complete(Unit)
                        releaseDirectHydration.await()
                        emit(snapshot)
                    }
                }
            }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            publish(value)
        }

        override suspend fun delete(key: TestKey) {
            publish(null)
        }
    }
}
