@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store.store5.mutablestore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
    fun cancellingWhileWaitingForLocalMutexHasNoEffects() =
        runTest {
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
    fun cancellingInsideLocalWriterDoesNotAdmitAndKeyRecovers() =
        runTest {
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
    fun cancellingAfterAdmissionRetainsTheWaitingWrite() =
        runTest {
            val fixture = LifecycleFixture(this)
            val gate = LifecycleGate()
            fixture.post = { _, value ->
                if (value == "A") gate.pause()
                UpdaterResult.Success.Typed(value)
            }
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
    fun cancellingUpdaterRetainsPendingWorkAndExistingMarker() =
        runTest {
            val fixture = LifecycleFixture(this)
            fixture.marker = 7L
            val gate = LifecycleGate()
            fixture.post = { _, _ ->
                gate.pause()
                UpdaterResult.Success.Typed("unused")
            }
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
    fun cancellingBeforeAcknowledgementLeavesBatchRetryable() =
        runTest {
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
    fun cancellingClearPreservesEarnedCallbacksAndWaitingCallersCachedSuccess() =
        runTest {
            val fixture = LifecycleFixture(this)
            val postGate = LifecycleGate()
            val clearGate = LifecycleGate()
            fixture.post = { _, value ->
                if (value == "A") {
                    postGate.pause()
                    UpdaterResult.Error.Message("retry")
                } else {
                    UpdaterResult.Success.Typed("saved")
                }
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
    fun thrownUpdaterExceptionPreservesItsCauseAndPendingWork() =
        runTest {
            val fixture = LifecycleFixture(this)
            val failure = IllegalArgumentException("updater failed")
            fixture.post = { _, _ -> throw failure }
            val response = fixture.write("A")
            val returnedError = assertIs<StoreWriteResponse.Error.Exception>(response).error
            assertIs<IllegalArgumentException>(returnedError)
            assertEquals(failure.message, returnedError.message)
            assertTrue(generateSequence<Throwable>(returnedError) { it.cause }.any { it === failure })
            assertTrue(fixture.completed.isEmpty())
            assertTrue(fixture.marker != null)
            fixture.post = { _, value -> UpdaterResult.Success.Typed(value) }
            assertIs<StoreWriteResponse.Success>(fixture.write("B"))
            assertEquals(listOf("A", "B"), fixture.completed)
            assertNull(fixture.marker)
        }

    @Test
    fun thrownAndReturnedUpdaterCancellationPropagateAndRemainRetryable() =
        runTest {
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
    fun activeJobLocalCancellationPropagatesWithoutAdmission() =
        runTest {
            val fixture = LifecycleFixture(this)
            fixture.beforeWrite = { _, _ -> throw CancellationException("local") }
            assertFailsWith<CancellationException> { fixture.write("A") }
            assertTrue(fixture.posted.isEmpty())
            fixture.beforeWrite = { _, _ -> }
            fixture.write("B")
            assertEquals(listOf("B"), fixture.completed)
        }

    @Test
    fun updaterRejectsRecursiveWriteReadAndBothClearsBeforeEffects() =
        runTest {
            for (operation in listOf("write", "read", "clearKey", "clearAll")) {
                val fixture = LifecycleFixture(this)
                var checked = false
                fixture.post = { _, value ->
                    fixture.assertRejected(operation)
                    checked = true
                    UpdaterResult.Success.Typed(value)
                }
                assertIs<StoreWriteResponse.Success>(fixture.write("A"))
                assertTrue(checked)
                assertEquals(listOf("k" to "A"), fixture.persisted)
                assertEquals(1, fixture.posted.size)
                assertEquals(0, fixture.deletes)
            }
        }

    @Test
    fun sourceWriterAndLatestReaderRejectRecursiveWritesBeforeEffects() =
        runTest {
            val fixture = LifecycleFixture(this)
            var writerChecks = 0
            fixture.beforeWrite = { _, _ ->
                fixture.assertRejected("write")
                writerChecks++
            }
            fixture.write("A")
            assertEquals(1, writerChecks)
            assertEquals(listOf("k" to "A"), fixture.persisted)
            fixture.beforeWrite = { _, _ -> }
            fixture.marker = 1L
            fixture.cache.invalidate("k")
            var readerChecks = 0
            fixture.beforeRead = {
                fixture.beforeRead = {}
                fixture.assertRejected("write")
                readerChecks++
            }
            fixture.read()
            assertTrue(readerChecks > 0)
            assertEquals(listOf("k" to "A"), fixture.persisted)
            assertEquals(listOf("A", "A"), fixture.posted.map { it.second })
        }

    @Test
    fun bookkeeperAdaptersRejectRecursiveOperationsBeforeEffects() =
        runTest {
            for (adapter in listOf("get", "set", "clear")) {
                val fixture = LifecycleFixture(this)
                var checks = 0
                val check: suspend () -> Unit = {
                    fixture.assertRejected("write")
                    fixture.assertRejected("read")
                    fixture.assertRejected("clearKey")
                    fixture.assertRejected("clearAll")
                    checks++
                }
                when (adapter) {
                    "get" -> fixture.beforeGet = check
                    "set" -> {
                        fixture.beforeSet = check
                        fixture.post = { _, _ -> UpdaterResult.Error.Message("failed") }
                    }
                    "clear" -> fixture.beforeClear = check
                }
                fixture.write("A")
                if (adapter == "get") fixture.read()
                assertTrue(checks > 0, adapter)
                assertEquals(listOf("k" to "A"), fixture.persisted)
                assertEquals(1, fixture.posted.size)
                assertEquals(0, fixture.deletes)
            }
        }

    @Test
    fun inheritedChildAndDifferentKeyCycleCannotReenterAncestorKey() =
        runTest {
            val fixture = LifecycleFixture(this)
            var childChecked = false
            var cycleChecked = false
            fixture.post = { key, value ->
                if (key == "k") {
                    coroutineScope {
                        async {
                            fixture.assertRejected("write")
                            childChecked = true
                        }.await()
                    }
                    assertIs<StoreWriteResponse.Success>(fixture.write("J", "j"))
                } else {
                    fixture.assertRejected("write")
                    cycleChecked = true
                }
                UpdaterResult.Success.Typed(value)
            }
            assertIs<StoreWriteResponse.Success>(fixture.write("A"))
            assertTrue(childChecked)
            assertTrue(cycleChecked)
            assertEquals(listOf("k" to "A", "j" to "J"), fixture.persisted)
            assertEquals(2, fixture.posted.size)
        }

    @Test
    fun anotherStoreWithSameKeyIsAllowed() =
        runTest {
            val outer = LifecycleFixture(this)
            val other = LifecycleFixture(this)
            outer.post = { _, value ->
                assertIs<StoreWriteResponse.Success>(other.write("nested"))
                UpdaterResult.Success.Typed(value)
            }
            outer.write("A")
            assertEquals(listOf("k" to "nested"), other.persisted)
        }

    @Test
    fun requestAndUpdaterCallbacksCanCompleteSameKeyWriteBeforeReturning() =
        runTest {
            for (updaterCallback in listOf(false, true)) {
                val fixture = LifecycleFixture(this)
                var reentered = false
                var provedImmediateCompletion = false
                val callback: () -> Unit = {
                    if (!reentered) {
                        reentered = true
                        var nestedResponse: StoreWriteResponse? = null
                        val nested = async(start = CoroutineStart.UNDISPATCHED) { nestedResponse = fixture.write("nested") }
                        assertTrue(nested.isCompleted, "Nested write must complete before callback returns")
                        assertIs<StoreWriteResponse.Success>(nestedResponse)
                        assertEquals("nested", fixture.local.value["k"])
                        provedImmediateCompletion = true
                    }
                }
                if (updaterCallback) fixture.onUpdaterSuccess = callback
                val callbacks = if (updaterCallback) emptyList() else listOf(callback)
                assertIs<StoreWriteResponse.Success>(fixture.write("A", callbacks = callbacks))
                assertTrue(reentered)
                assertTrue(provedImmediateCompletion)
                assertEquals(2, fixture.posted.size)
            }
        }

    @Test
    fun ordinaryCallbackFailuresDoNotChangeSuccessOrSkipRemainingCallbacks() =
        runTest {
            val fixture = LifecycleFixture(this)
            val events = mutableListOf<String>()
            fixture.onUpdaterSuccess = {
                events.add("updater")
                error("updater callback")
            }
            val response =
                fixture.write(
                    "A",
                    callbacks =
                        listOf({
                            events.add("first")
                            error("request callback")
                        }, { events.add("last") }),
                )
            assertIs<StoreWriteResponse.Success>(response)
            assertEquals(listOf("updater", "first", "last"), events)
            assertEquals(listOf("A"), fixture.completed)
            assertEquals("A", fixture.remote["k"])
            assertTrue(fixture.logger.errorLogs.size >= 2)
            fixture.read()
            assertEquals(1, fixture.posted.size)
        }

    @Test
    fun callbackCancellationFinishesEarnedBatchBeforePropagating() =
        runTest {
            for (updaterCallback in listOf(false, true)) {
                val fixture = LifecycleFixture(this)
                val events = mutableListOf<String>()
                fixture.post = { _, _ -> UpdaterResult.Error.Message("retry") }
                fixture.write(
                    "A",
                    callbacks =
                        listOf({
                            events.add("first")
                            throw CancellationException("request callback")
                        }, { events.add("last") }),
                )
                fixture.post = { _, value -> UpdaterResult.Success.Typed(value) }
                fixture.onUpdaterSuccess = {
                    events.add("updater")
                    if (updaterCallback) throw CancellationException("updater callback")
                }
                assertFailsWith<CancellationException> {
                    fixture.write("B", callbacks = listOf({ events.add("lastB") }))
                }
                assertEquals(listOf("updater", "first", "last", "updater", "lastB"), events)
                assertEquals(listOf("A", "B"), fixture.completed)
                assertEquals("B", fixture.remote["k"])
                fixture.onUpdaterSuccess = {}
                fixture.read()
                assertEquals(2, fixture.posted.size)
                assertIs<StoreWriteResponse.Success>(fixture.write("C"))
            }
        }

    @Test
    fun bookkeepingFalseAndOrdinaryFailuresPreserveOutcomesAndRecoverability() =
        runTest {
            for (failure in listOf("setFalse", "setThrow", "clearFalse", "clearThrow", "getThrow")) {
                val fixture = LifecycleFixture(this)
                val failedUpdate = UpdaterResult.Error.Message("original failure")
                when (failure) {
                    "setFalse" -> {
                        fixture.setResult = false
                        fixture.post = { _, _ -> failedUpdate }
                    }
                    "setThrow" -> {
                        fixture.beforeSet = { error("set failure") }
                        fixture.post = { _, _ -> failedUpdate }
                    }
                    "clearFalse" -> fixture.clearResult = false
                    "clearThrow" -> fixture.beforeClear = { error("clear failure") }
                    "getThrow" -> fixture.beforeGet = { error("get failure") }
                }
                val response = fixture.write("A")
                if (failure.startsWith("set")) {
                    assertEquals(StoreWriteResponse.Error.Message("original failure"), response)
                    assertTrue(fixture.completed.isEmpty())
                } else {
                    assertIs<StoreWriteResponse.Success>(response)
                    assertEquals(listOf("A"), fixture.completed)
                }
                if (failure == "getThrow") fixture.read()
                if (failure != "clearFalse") assertTrue(fixture.logger.errorLogs.isNotEmpty(), failure)
                fixture.beforeGet = {}
                fixture.beforeSet = {}
                fixture.beforeClear = {}
                fixture.setResult = true
                fixture.clearResult = true
                fixture.post = { _, value -> UpdaterResult.Success.Typed(value) }
                assertIs<StoreWriteResponse.Success>(fixture.write("B"))
                assertEquals(listOf("A", "B"), fixture.completed)
                assertTrue(fixture.failedCallbacks.isEmpty())
            }
        }

    @Test
    fun cancellingEagerPostPreservesPendingCallbacksAndMarker() =
        runTest {
            val fixture = LifecycleFixture(this)
            fixture.post = { _, _ -> UpdaterResult.Error.Message("retry") }
            fixture.write("A")
            val marker = fixture.marker
            val gate = LifecycleGate()
            fixture.post = { _, value ->
                gate.pause()
                UpdaterResult.Success.Typed(value)
            }
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
    fun eagerBookkeeperCancellationIsNotSwallowed() =
        runTest {
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

    suspend fun pause() {
        entered.complete(Unit)
        released.await()
    }

    fun open() {
        released.complete(Unit)
    }
}

private class LifecycleFixture(scope: TestScope, beforeAcknowledgement: suspend () -> Unit = {}) {
    val local = MutableStateFlow<Map<String, String>>(emptyMap())
    val persisted = mutableListOf<Pair<String, String>>()
    val posted = mutableListOf<Pair<String, String>>()
    val remote = mutableMapOf<String, String>()
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
    val store =
        RealMutableStore(
            delegate =
                testStore<String, String, String, String>(
                    dispatcher = StandardTestDispatcher(scope.testScheduler),
                    scope = scope.backgroundScope,
                    fetcher = Fetcher.of { _: String -> error("Unexpected fetch") },
                    sourceOfTruth =
                        SourceOfTruth.of(
                            reader = { key: String ->
                                flow {
                                    beforeRead()
                                    emitAll(local.map { it[key] })
                                }
                            },
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
            updater =
                Updater.by<String, String, String>(
                    post = { key, value ->
                        posted.add(key to value)
                        post(key, value).also { result -> if (result is UpdaterResult.Success) remote[key] = value }
                    },
                    onCompletion = OnUpdaterCompletion(onSuccess = { onUpdaterSuccess() }, onFailure = {}),
                ),
            bookkeeper =
                object : Bookkeeper<String> {
                    override suspend fun getLastFailedSync(key: String): Long? {
                        beforeGet()
                        return marker
                    }

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

                    override suspend fun clearAll(): Boolean {
                        marker = null
                        return true
                    }
                },
            logger = logger,
            beforeAcknowledgementCommit = beforeAcknowledgement,
        )

    suspend fun write(value: String, key: String = "k", callbacks: List<() -> Unit> = emptyList()): StoreWriteResponse =
        store.write(
            StoreWriteRequest.of<String, String, String>(
                key = key,
                value = value,
                onCompletions =
                    listOf(OnStoreWriteCompletion(onSuccess = { completed.add(value) }, onFailure = { failedCallbacks.add(it) })) +
                        callbacks.map { callback -> OnStoreWriteCompletion(onSuccess = { callback() }, onFailure = {}) },
            ),
        )

    suspend fun read() = store.stream<String>(StoreReadRequest.localOnly("k")).first()

    suspend fun assertRejected(operation: String) {
        val error =
            when (operation) {
                "write" -> {
                    try {
                        val response = write("recursive")
                        assertIs<StoreWriteResponse.Error.Exception>(response).error
                    } catch (error: IllegalStateException) {
                        error
                    }
                }
                "read" -> assertFailsWith<IllegalStateException> { read() }
                "clearKey" -> assertFailsWith<IllegalStateException> { store.clear("k") }
                else -> assertFailsWith<IllegalStateException> { store.clear() }
            }
        assertIs<IllegalStateException>(error)
        assertTrue(error.message.orEmpty().contains("Recursive"))
    }
}
