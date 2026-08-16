package org.mobilenativefoundation.store6.core.internal

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FakeWallClock
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.TestKey
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Records whether a freshly attached reader receives the previous Ready snapshot while a
 * projection recompute is pending for an accepted overlay change, then asserts convergence after
 * the recompute completes.
 *
 * The probe reconstructs the alias-activation handoff's core-visible shape without the
 * mutations machinery: an overlay whose projected value changes, a change signal accepted by
 * the key's single writer, no residence-revision advance, and the recompute held at the
 * engine's own projection-apply gate while a fresh collector attaches. The probe asserts
 * convergence. The printed first-frame observations are measurements, not behavioral assertions.
 */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class ProjectionRecomputeVisibilityProbeTest {
    private val key = TestKey("recompute-visibility-probe")

    @Test
    fun freshAttachDuringHeldRecompute_recordFirstFrameThenConverge() = runTest {
        val signals = MutableSharedFlow<StoreKey>(replay = 1)
        val projected = MutableStateFlow("head")
        val gateArmed = MutableStateFlow(false)
        val gate = SuspendGate()
        val overlay =
            object : Overlay<TestKey, String> {
                override val changes: Flow<StoreKey> = signals

                override fun apply(
                    key: TestKey,
                    base: String?,
                ): String = projected.value
            }
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(Dispatchers.Default + job)
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { FetcherResult.Success("base") },
                sot = SharedFlowSourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(0L),
                engineScope = scope,
                telemetry = null,
                overlay = overlay,
                beforeProjectionApplyTestGate = { if (gateArmed.value) gate.pause() },
            )

        try {
            engine.stream(Freshness.CachedOrFetch).test {
                awaitDataValue("head")
                cancelAndIgnoreRemainingEvents()
            }
            println("PROBE032 phase1 initial projection 'head' observed")
            withContext(Dispatchers.Default) {
                signals.subscriptionCount.first { count -> count > 0 }
            }

            projected.value = "head+tail"
            gateArmed.value = true
            signals.emit(key)
            gate.awaitEntered()
            println("PROBE032 phase2 recompute held at the apply gate")

            val frames = mutableListOf<String>()
            val firstFrame = CompletableDeferred<String>()
            val watchdog =
                backgroundScope.launch(Dispatchers.Default) {
                    delay(3_000)
                    if (!firstFrame.isCompleted) {
                        println("PROBE032 measurement=NO_FIRST_FRAME_WHILE_HELD (attach waited out the 3s hold)")
                        gateArmed.value = false
                        gate.release()
                    }
                }
            engine.stream(Freshness.LocalOnly).test {
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String>) {
                        frames += item.value
                        if (!firstFrame.isCompleted) {
                            firstFrame.complete(item.value)
                            if (gateArmed.value) {
                                println("PROBE032 measurement=FIRST_FRAME_WHILE_HELD value=${item.value}")
                            }
                            gateArmed.value = false
                            gate.release()
                        }
                        if (item.value == "head+tail") break
                    } else {
                        frames += item::class.simpleName.orEmpty()
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }
            watchdog.cancel()

            println("PROBE032 first_frame=${firstFrame.await()}")
            println("PROBE032 frames=${frames.joinToString("|")}")
            assertEquals("head+tail", frames.last())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun freshAttachDuringFrozenWriter_preAcceptanceWindow_recordFirstFrameThenConverge() = runTest {
        val signals = MutableSharedFlow<StoreKey>(replay = 1)
        val projected = MutableStateFlow("head")
        val dispatcher = FreezableDispatcher()
        val overlay =
            object : Overlay<TestKey, String> {
                override val changes: Flow<StoreKey> = signals

                override fun apply(
                    key: TestKey,
                    base: String?,
                ): String = projected.value
            }
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(dispatcher + job)
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { FetcherResult.Success("base") },
                sot = SharedFlowSourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(0L),
                engineScope = scope,
                telemetry = null,
                overlay = overlay,
            )

        try {
            engine.stream(Freshness.CachedOrFetch).test {
                awaitDataValue("head")
                cancelAndIgnoreRemainingEvents()
            }
            println("PROBE032B phase1 initial projection 'head' observed")
            withContext(Dispatchers.Default) {
                signals.subscriptionCount.first { count -> count > 0 }
            }
            withContext(Dispatchers.Default) { delay(200) }

            dispatcher.freeze()
            projected.value = "head+tail"
            signals.emit(key)
            println("PROBE032B phase2 writer frozen pre-acceptance; change emitted; snapshot should still be Ready('head')")

            val frames = mutableListOf<String>()
            val firstFrame = CompletableDeferred<String>()
            val watchdog =
                backgroundScope.launch(Dispatchers.Default) {
                    delay(3_000)
                    if (!firstFrame.isCompleted) {
                        println("PROBE032B measurement=NO_FIRST_FRAME_WHILE_FROZEN (attach waited out the 3s freeze)")
                    }
                    dispatcher.thaw()
                }
            engine.stream(Freshness.LocalOnly).test {
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String>) {
                        frames += item.value
                        if (!firstFrame.isCompleted) {
                            firstFrame.complete(item.value)
                            if (dispatcher.isFrozen()) {
                                println("PROBE032B measurement=FIRST_FRAME_WHILE_FROZEN value=${item.value}")
                            }
                            dispatcher.thaw()
                        }
                        if (item.value == "head+tail") break
                    } else {
                        frames += item::class.simpleName.orEmpty()
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }
            watchdog.cancel()

            println("PROBE032B first_frame=${firstFrame.await()}")
            println("PROBE032B frames=${frames.joinToString("|")}")
            assertEquals("head+tail", frames.last())
        } finally {
            dispatcher.thaw()
            job.cancel()
        }
    }

    private class FreezableDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private data class DispatcherState(
            val frozen: Boolean,
            val parked: List<Pair<kotlin.coroutines.CoroutineContext, Runnable>>,
        )

        private val state = MutableStateFlow(DispatcherState(frozen = false, parked = emptyList()))

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            while (true) {
                val current = state.value
                if (!current.frozen) {
                    Dispatchers.Default.dispatch(context, block)
                    return
                }
                val next = current.copy(parked = current.parked + (context to block))
                if (state.compareAndSet(current, next)) return
            }
        }

        fun freeze() {
            while (true) {
                val current = state.value
                if (state.compareAndSet(current, current.copy(frozen = true))) return
            }
        }

        fun isFrozen(): Boolean = state.value.frozen

        fun thaw() {
            while (true) {
                val current = state.value
                if (state.compareAndSet(current, DispatcherState(frozen = false, parked = emptyList()))) {
                    current.parked.forEach { entry -> Dispatchers.Default.dispatch(entry.first, entry.second) }
                    return
                }
            }
        }
    }

    private class SuspendGate {
        private val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun pause() {
            entered.complete(Unit)
            released.await()
        }

        suspend fun awaitEntered() {
            withContext(Dispatchers.Default) {
                entered.await()
            }
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<StoreResult<String>>.awaitDataValue(
        expected: String,
    ): StoreResult.Data<String> {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data<String> && item.value == expected) return item
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
