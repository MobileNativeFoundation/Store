@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store.store5.mutablestore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.OnUpdaterCompletion
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.OnStoreWriteCompletion
import org.mobilenativefoundation.store.store5.impl.RealMutableStore
import org.mobilenativefoundation.store.store5.mutablestore.util.TestConverter
import org.mobilenativefoundation.store.store5.mutablestore.util.TestLogger
import org.mobilenativefoundation.store.store5.mutablestore.util.TestValidator
import org.mobilenativefoundation.store.store5.mutablestore.util.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableStoreLifecycleTest {
    @Test
    fun cancellingWhileWaitingForLocalMutexHasNoEffects() = runTest {
        val fixture = LifecycleFixture(this)
        val gate = LifecycleGate()
        fixture.beforeWrite = { _, value -> if (value == "A") gate.pause() }
        val first = async { fixture.write("A") }
        gate.entered.await()
        val cancelled = async { fixture.write("B") }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(fixture.persisted.isEmpty())
        assertTrue(fixture.posted.isEmpty())
        gate.open()
        assertIs<StoreWriteResponse.Success>(first.await())
        assertIs<StoreWriteResponse.Success>(fixture.write("C"))
        assertEquals(listOf("A", "C"), fixture.persisted.map { it.second })
        assertEquals(listOf("A", "C"), fixture.completed)
    }

    @Test
    fun cancellingInsideLocalWriterDoesNotAdmitAndKeyRecovers() = runTest {
        val fixture = LifecycleFixture(this)
        val gate = LifecycleGate()
        fixture.beforeWrite = { _, value -> if (value == "A") gate.pause() }
        val cancelled = async { fixture.write("A") }
        gate.entered.await()
        cancelled.cancelAndJoin()
        assertTrue(fixture.posted.isEmpty())
        assertTrue(fixture.completed.isEmpty())
        assertIs<StoreWriteResponse.Success>(fixture.write("B"))
        assertEquals(listOf("B"), fixture.persisted.map { it.second })
        assertEquals(listOf("B"), fixture.completed)
    }

    @Test
    fun cancellingAfterAdmissionRetainsTheWaitingWrite() = runTest {
        val fixture = LifecycleFixture(this)
        val gate = LifecycleGate()
        fixture.post = { _, value -> if (value == "A") gate.pause(); UpdaterResult.Success.Typed(value) }
        val first = async { fixture.write("A") }
        gate.entered.await()
        val cancelled = async { fixture.write("B") }
        runCurrent()
        assertEquals("B", fixture.local.value["k"])
        cancelled.cancelAndJoin()
        gate.open()
        first.await()
        fixture.write("C")
        assertEquals(listOf("A", "C"), fixture.posted.map { it.second })
        assertEquals(listOf("A", "B", "C"), fixture.completed)
    }

    @Test
    fun cancellingUpdaterRetainsPendingWorkAndExistingMarker() = runTest {
        val fixture = LifecycleFixture(this)
        fixture.marker = 7L
        val gate = LifecycleGate()
        fixture.post = { _, _ -> gate.pause(); UpdaterResult.Success.Typed("unused") }
        val cancelled = async { fixture.write("A") }
        gate.entered.await()
        cancelled.cancelAndJoin()
        assertEquals(7L, fixture.marker)
        assertEquals(0, fixture.clears)
        assertTrue(fixture.completed.isEmpty())
        fixture.post = { _, value -> UpdaterResult.Success.Typed(value) }
        fixture.write("B")
        assertEquals(listOf("A", "B"), fixture.completed)
        assertNull(fixture.marker)
    }

    @Test
    fun cancellingBeforeAcknowledgementLeavesBatchRetryable() = runTest {
        val gate = LifecycleGate()
        var pause = true
        val fixture = LifecycleFixture(this, beforeAcknowledgement = { if (pause) gate.pause() })
        val cancelled = async { fixture.write("A") }
        gate.entered.await()
        cancelled.cancelAndJoin()
        assertTrue(fixture.completed.isEmpty())
        assertEquals(0, fixture.clears)
        pause = false
        fixture.write("B")
        assertEquals(listOf("A", "B"), fixture.completed)
        assertEquals(listOf("A", "B"), fixture.posted.map { it.second })
    }

    @Test
    fun cancellingClearPreservesEarnedCallbacksAndWaitingCallersCachedSuccess() = runTest {
        val fixture = LifecycleFixture(this)
        val postGate = LifecycleGate()
        val clearGate = LifecycleGate()
        fixture.post = { _, value ->
            if (value == "A") { postGate.pause(); UpdaterResult.Error.Message("retry") }
            else UpdaterResult.Success.Typed("saved")
        }
        fixture.beforeClear = { clearGate.pause() }
        val first = async { fixture.write("A") }
        postGate.entered.await()
        val cancelled = async { fixture.write("B") }
        val waiting = async { fixture.write("C") }
        runCurrent()
        postGate.open()
        clearGate.entered.await()
        assertIs<StoreWriteResponse.Error>(first.await())
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertEquals(StoreWriteResponse.Success.Typed("saved"), waiting.await())
        assertEquals(listOf("A", "B", "C"), fixture.completed)
        assertEquals(listOf("A", "C"), fixture.posted.map { it.second })
        fixture.beforeClear = {}
        assertIs<StoreWriteResponse.Success>(fixture.write("D"))
    }

    @Test
    fun thrownUpdaterExceptionPreservesItsCauseAndPendingWork() = runTest {
        val fixture = LifecycleFixture(this)
        val failure = IllegalArgumentException("updater failed")
        fixture.post = { _, _ -> throw failure }
        val response = fixture.write("A")
        assertEquals(failure, assertIs<StoreWriteResponse.Error.Exception>(response).error)
        assertTrue(fixture.completed.isEmpty())
        assertTrue(fixture.marker != null)
        fixture.post = { _, value -> UpdaterResult.Success.Typed(value) }
        assertIs<StoreWriteResponse.Success>(fixture.write("B"))
        assertEquals(listOf("A", "B"), fixture.completed)
        assertNull(fixture.marker)
    }

    @Test
    fun thrownAndReturnedUpdaterCancellationPropagateAndRemainRetryable() = runTest {
        for (returned in listOf(false, true)) {
            val fixture = LifecycleFixture(this)
            fixture.post = { _, _ ->
                val cancellation = CancellationException("updater")
                if (returned) UpdaterResult.Error.Exception(cancellation) else throw cancellation
            }
            assertFailsWith<CancellationException> { fixture.write("A") }
            assertTrue(fixture.completed.isEmpty())
            fixture.post = { _, value -> UpdaterResult.Success.Typed(value) }
            fixture.write("B")
            assertEquals(listOf("A", "B"), fixture.completed)
        }
    }

    @Test
    fun activeJobLocalCancellationPropagatesWithoutAdmission() = runTest {
        val fixture = LifecycleFixture(this)
        fixture.beforeWrite = { _, _ -> throw CancellationException("local") }
        assertFailsWith<CancellationException> { fixture.write("A") }
        assertTrue(fixture.posted.isEmpty())
        fixture.beforeWrite = { _, _ -> }
        fixture.write("B")
        assertEquals(listOf("B"), fixture.completed)
    }

    @Test
    fun cancellingEagerPostPreservesPendingCallbacksAndMarker() = runTest {
        val fixture = LifecycleFixture(this)
        fixture.post = { _, _ -> UpdaterResult.Error.Message("retry") }
        fixture.write("A")
        val marker = fixture.marker
        val gate = LifecycleGate()
        fixture.post = { _, value -> gate.pause(); UpdaterResult.Success.Typed(value) }
        val reader = async { fixture.read() }
        gate.entered.await()
        reader.cancelAndJoin()
        assertEquals(marker, fixture.marker)
        assertTrue(fixture.completed.isEmpty())
        assertEquals(0, fixture.clears)
        fixture.post = { _, value -> UpdaterResult.Success.Typed(value) }
        fixture.read()
        assertEquals(listOf("A"), fixture.completed)
        assertNull(fixture.marker)
    }

    @Test
    fun eagerBookkeeperCancellationIsNotSwallowed() = runTest {
        val fixture = LifecycleFixture(this)
        fixture.write("A")
        fixture.beforeGet = { throw CancellationException("lookup") }
        assertFailsWith<CancellationException> { fixture.read() }
        fixture.beforeGet = {}
        fixture.read()
        assertEquals(1, fixture.posted.size)
    }
}

private class LifecycleGate {
    val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun pause() { entered.complete(Unit); released.await() }
    fun open() { released.complete(Unit) }
}

private class LifecycleFixture(scope: TestScope, beforeAcknowledgement: suspend () -> Unit = {}) {
    val local = MutableStateFlow<Map<String, String>>(emptyMap())
    val persisted = mutableListOf<Pair<String, String>>()
    val posted = mutableListOf<Pair<String, String>>()
    val completed = mutableListOf<String>()
    val failedCallbacks = mutableListOf<StoreWriteResponse.Error>()
    val logger = TestLogger()
    val cache = CacheBuilder<String, String>().build()
    var marker: Long? = null
    var clears = 0
    var deletes = 0
    var setResult = true
    var clearResult = true
    var beforeWrite: suspend (String, String) -> Unit = { _, _ -> }
    var beforeRead: suspend () -> Unit = {}
    var beforeGet: suspend () -> Unit = {}
    var beforeSet: suspend () -> Unit = {}
    var beforeClear: suspend () -> Unit = {}
    var post: suspend (String, String) -> UpdaterResult = { _, value -> UpdaterResult.Success.Typed(value) }
    var onUpdaterSuccess: () -> Unit = {}
    val store = RealMutableStore(
        delegate = testStore<String, String, String, String>(
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            scope = scope.backgroundScope,
            fetcher = Fetcher.of { _: String -> error("Unexpected fetch") },
            sourceOfTruth = SourceOfTruth.of(
                reader = { key: String -> flow { beforeRead(); emitAll(local.map { it[key] }) } },
                writer = { key: String, value: String ->
                    beforeWrite(key, value)
                    local.value = local.value + (key to value)
                    persisted.add(key to value)
                },
                delete = { _: String -> deletes++ },
                deleteAll = { deletes++ },
            ),
            converter = TestConverter(),
            validator = TestValidator(),
            memoryCache = cache,
        ),
        updater = Updater.by<String, String, String>(
            post = { key, value -> posted.add(key to value); post(key, value) },
            onCompletion = OnUpdaterCompletion(onSuccess = { onUpdaterSuccess() }, onFailure = {}),
        ),
        bookkeeper = object : Bookkeeper<String> {
            override suspend fun getLastFailedSync(key: String): Long? { beforeGet(); return marker }
            override suspend fun setLastFailedSync(key: String, timestamp: Long): Boolean {
                beforeSet()
                if (setResult) marker = timestamp
                return setResult
            }
            override suspend fun clear(key: String): Boolean {
                clears++
                beforeClear()
                if (clearResult) marker = null
                return clearResult
            }
            override suspend fun clearAll(): Boolean { marker = null; return true }
        },
        logger = logger,
        beforeAcknowledgementCommit = beforeAcknowledgement,
    )

    suspend fun write(value: String, key: String = "k", callbacks: List<() -> Unit> = emptyList()): StoreWriteResponse =
        store.write(StoreWriteRequest.of<String, String, String>(
            key = key,
            value = value,
            onCompletions = listOf(OnStoreWriteCompletion(onSuccess = { completed.add(value) }, onFailure = { failedCallbacks.add(it) })) +
                callbacks.map { callback -> OnStoreWriteCompletion(onSuccess = { callback() }, onFailure = {}) },
        ))

    suspend fun read() = store.stream<String>(StoreReadRequest.localOnly("k")).first()

    suspend fun assertRejected(operation: String) {
        val error = when (operation) {
            "write" -> {
                try {
                    val response = write("recursive")
                    assertIs<StoreWriteResponse.Error.Exception>(response).error
                } catch (error: IllegalStateException) { error }
            }
            "read" -> assertFailsWith<IllegalStateException> { read() }
            "clearKey" -> assertFailsWith<IllegalStateException> { store.clear("k") }
            else -> assertFailsWith<IllegalStateException> { store.clear() }
        }
        assertIs<IllegalStateException>(error)
        assertTrue(error.message.orEmpty().contains("Recursive"))
    }
}
