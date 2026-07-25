package org.mobilenativefoundation.store6.core.internal

import app.cash.turbine.test
import app.cash.turbine.testIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FakeWallClock
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.TestKey
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FetchPlan
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.FreshnessContext
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.SingleRowTestSourceOfTruth
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val COLD_OBSERVATION_HOLD = 60.milliseconds
private val WRITER_CURRENT_DELIVERY_GAP = 1.milliseconds

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class KeyEnginePlanningTest {
    @Test
    fun hydration_reusesWrittenAtButStripsOldEtagAfterExternalReplacement() = runTest {
        val key = TestKey("external-replacement-meta")
        val durableSot = InMemorySourceOfTruth<TestKey, String>()
        val durableBookkeeper = InMemoryBookkeeper()
        val clock = FakeWallClock(now = 123L)
        val first =
            KeyEngine(
                key,
                KeyId.from(key),
                ResultFetcher { FetcherResult.Success("engine", etag = "old-etag") },
                durableSot,
                durableBookkeeper,
                DefaultFreshnessValidator,
                clock,
                backgroundScope,
            )
        assertEquals("engine", first.get(Freshness.MustBeFresh))
        durableSot.write(key, "external")

        var observed: FreshnessContext? = null
        val capturingValidator =
            object : FreshnessValidator {
                override fun plan(context: FreshnessContext): FetchPlan {
                    observed = context
                    return FetchPlan.Skip
                }
            }
        val restarted =
            KeyEngine(
                key,
                KeyId.from(key),
                ResultFetcher { error("LocalOnly hydration must not fetch") },
                durableSot,
                durableBookkeeper,
                capturingValidator,
                clock,
                backgroundScope,
            )

        assertEquals("external", restarted.get(Freshness.LocalOnly))
        assertEquals(123L, observed?.meta?.writtenAtEpochMillis)
        assertNull(observed?.meta?.etag, "external rows must never inherit the old engine ETag")
    }

    @Test
    fun hydration_durablyStaleRowIsEpochStaleAndStatusVisibleToPlanning() = runTest {
        val key = TestKey("durably-stale-hydration")
        val durableSot = InMemorySourceOfTruth<TestKey, String>()
        val durableBookkeeper = InMemoryBookkeeper()
        val first =
            KeyEngine(
                key,
                KeyId.from(key),
                ResultFetcher { FetcherResult.Success("v1", etag = "e1") },
                durableSot,
                durableBookkeeper,
                DefaultFreshnessValidator,
                FakeWallClock(now = 10L),
                backgroundScope,
            )
        assertEquals("v1", first.get(Freshness.MustBeFresh))
        durableBookkeeper.markStale(key)

        var observed: FreshnessContext? = null
        val restarted =
            KeyEngine(
                key,
                KeyId.from(key),
                ResultFetcher { error("LocalOnly hydration must not fetch") },
                durableSot,
                durableBookkeeper,
                object : FreshnessValidator {
                    override fun plan(context: FreshnessContext): FetchPlan {
                        observed = context
                        return FetchPlan.Skip
                    }
                },
                FakeWallClock(now = 20L),
                backgroundScope,
            )

        restarted.stream(Freshness.LocalOnly).test {
            val data = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v1", data.value)
            assertTrue(data.isStale, "durably stale hydration must be epoch-stale")
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, observed?.status?.durablyStale)
    }

    @Test
    fun exactOwnerNotModified_emitsRevalidatedAgeAndNoThirdFetchWhileSuccessIsGated() = runTest {
        val key = TestKey("owner-revalidated")
        val keyId = KeyId.from(key)
        val durableBookkeeper = PreDelegateSuccessGateBookkeeper()
        val clock = FakeWallClock(now = 100L)
        val secondFetchEntered = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key,
                keyId,
                ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> {
                            secondFetchEntered.complete(Unit)
                            releaseSecondFetch.await()
                            FetcherResult.NotModified("e1")
                        }
                        else -> error("unexpected fetch call $calls")
                    }
                },
                InMemorySourceOfTruth(),
                durableBookkeeper,
                DefaultFreshnessValidator,
                clock,
                backgroundScope,
            )
        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        engine.invalidate()
        clock.now = 150L
        durableBookkeeper.gateNextSuccess()

        engine.stream(Freshness.CachedOrFetch).test {
            try {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                withTimeout(1_000) { secondFetchEntered.await() }
                val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
                val baselineRevision = assertNotNull(ticket.residenceRevisionAtLaunch)

                releaseSecondFetch.complete(Unit)
                withTimeout(1_000) { durableBookkeeper.successEntered.await() }
                val early = assertIs<FetchDisposition.Revalidated>(ticket.disposition.value)
                assertEquals(true, durableBookkeeper.status(key)?.durablyStale)
                assertEquals(2, calls, "pre-success stale status must not manufacture a third fetch")

                durableBookkeeper.releaseSuccess.complete(Unit)
                val outcome =
                    assertIs<FetchOutcome.Revalidated>(withTimeout(1_000) { ticket.outcome.await() })
                assertEquals(baselineRevision + 1L, outcome.residenceRevision)
                assertSame(early.envelope, outcome.envelope)
                assertSame(
                    outcome.envelope,
                    assertIs<FetchDisposition.Revalidated>(ticket.disposition.value).envelope,
                )

                var publicResult = awaitItem()
                while (publicResult is StoreResult.Data && publicResult.isStale) {
                    publicResult = awaitItem()
                }
                val revalidated = assertIs<StoreResult.Revalidated>(publicResult)
                assertEquals(50L, revalidated.age.inWholeMilliseconds)
                cancelAndIgnoreRemainingEvents()
            } finally {
                releaseSecondFetch.complete(Unit)
                durableBookkeeper.releaseSuccess.complete(Unit)
            }
        }
        assertEquals("v1", engine.get(Freshness.CachedOrFetch))
        assertEquals(2, calls)
    }

    @Test
    fun completedExactOwnerNotModified_rejectsStaleStatusSnapshotWithoutThirdFetch() = runTest {
        val key = TestKey("owner-revalidated-stale-status")
        val bookkeeper = PostDelegateStatusGateBookkeeper()
        val secondFetchEntered = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        val thirdFetchEntered = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher =
                    ResultFetcher {
                        when (++calls) {
                            1 -> FetcherResult.Success("v1", etag = "e1")
                            2 -> {
                                secondFetchEntered.complete(Unit)
                                releaseSecondFetch.await()
                                FetcherResult.NotModified("e1")
                            }
                            3 -> {
                                thirdFetchEntered.complete(Unit)
                                FetcherResult.Success("v2", etag = "e2")
                            }
                            else -> error("unexpected fetch call $calls")
                        }
                    },
                sot = InMemorySourceOfTruth(),
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 100L),
                engineScope = backgroundScope,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        engine.invalidate()
        val owner = async { engine.get(Freshness.MustBeFresh) }
        secondFetchEntered.await()
        val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket

        bookkeeper.gateStatusCall(number = 2)
        val staleWaiter = async { engine.get(Freshness.CachedOrFetch) }
        bookkeeper.statusEntered.await()

        releaseSecondFetch.complete(Unit)
        assertIs<FetchOutcome.Revalidated>(ticket.outcome.await())
        bookkeeper.releaseStatus.complete(Unit)

        assertEquals("v1", owner.await())
        assertEquals("v1", staleWaiter.await())
        testScheduler.runCurrent()
        assertFalse(thirdFetchEntered.isCompleted)
        assertEquals(2, calls)
        assertIs<FetchSlot.Idle>(engine.state.value.fetch)
        assertEquals("v2", engine.get(Freshness.MustBeFresh))
        assertTrue(thirdFetchEntered.isCompleted)
        assertEquals(3, calls, "a later MustBeFresh demand must not reuse the old 304 owner")
    }

    // T2E ruling (017 post-merge): a 304 that launched against a null residence baseline but
    // finds residence present at commit is an obsolete launch snapshot, not an adapter-contract
    // violation. It must classify ObsoleteRevalidation and self-heal by replanning once.
    @Test
    fun coldBaselineNotModified_hydratedBeforeCommit_classifiesObsoleteAndReplans() = runTest {
        val key = TestKey("cold-304-hydrated")
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        val firstFetchEntered = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            firstFetchEntered.complete(Unit)
                            releaseFirstFetch.await()
                            FetcherResult.NotModified("e1")
                        }
                        2 -> FetcherResult.NotModified("e1")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        engine.stream(Freshness.CachedOrFetch).test {
            assertIs<StoreResult.Loading>(awaitItem())
            firstFetchEntered.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            assertNull(ticket.residenceRevisionAtLaunch)

            sourceOfTruth.write(key, "external")
            assertEquals("external", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            releaseFirstFetch.complete(Unit)
            assertIs<FetchOutcome.ObsoleteRevalidation>(ticket.outcome.await())

            while (true) {
                when (val item = awaitItem()) {
                    is StoreResult.Data -> assertEquals("external", item.value)
                    is StoreResult.Revalidated -> break
                    else -> fail("unexpected lifecycle item ${item::class.simpleName}")
                }
            }
            testScheduler.runCurrent()
            assertEquals(2, calls, "the cold-baseline 304 self-heal replans exactly once")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun trulyColdNotModified_staysTypedMissingAdapterContractFailure() = runTest {
        val key = TestKey("cold-304-empty")
        val firstFetchEntered = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            firstFetchEntered.complete(Unit)
                            releaseFirstFetch.await()
                            FetcherResult.NotModified("e1")
                        }
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = InMemorySourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        engine.stream(Freshness.CachedOrFetch).test {
            assertIs<StoreResult.Loading>(awaitItem())
            firstFetchEntered.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            assertNull(ticket.residenceRevisionAtLaunch)

            releaseFirstFetch.complete(Unit)
            val failed = assertIs<FetchOutcome.Failed>(ticket.outcome.await())
            assertIs<StoreError.Missing>(failed.exception.error)

            val failure = assertIs<StoreResult.Error>(awaitItem())
            assertIs<StoreError.Missing>(failure.error)
            assertFalse(failure.servedStale)
            testScheduler.runCurrent()
            assertEquals(1, calls, "a truly cold 304 is terminal and must not replan")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun exactOwnerNotModified_backwardClockEmitsZeroAge() = runTest {
        val key = TestKey("owner-revalidated-backward-clock")
        val clock = FakeWallClock(now = 100L)
        var calls = 0
        val engine =
            KeyEngine(
                key,
                KeyId.from(key),
                ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        else -> FetcherResult.NotModified("e1")
                    }
                },
                InMemorySourceOfTruth(),
                InMemoryBookkeeper(),
                DefaultFreshnessValidator,
                clock,
                backgroundScope,
            )
        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        clock.now = 50L

        engine.stream(Freshness.MustBeFresh).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals(Duration.ZERO, assertIs<StoreResult.Revalidated>(awaitItem()).age)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun exactOwnerNotModified_nullPreRefreshMetadataEmitsZeroAge() = runTest {
        val key = TestKey("owner-revalidated-null-meta")
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        sourceOfTruth.write(key, "seed")
        val engine =
            KeyEngine(
                key,
                KeyId.from(key),
                ResultFetcher { FetcherResult.NotModified("e1") },
                sourceOfTruth,
                InMemoryBookkeeper(),
                DefaultFreshnessValidator,
                FakeWallClock(now = 50L),
                backgroundScope,
            )
        assertEquals("seed", engine.get(Freshness.LocalOnly))

        engine.stream(Freshness.MustBeFresh).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals(Duration.ZERO, assertIs<StoreResult.Revalidated>(awaitItem()).age)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun exactOwnerNotModified_overflowAgeSaturatesToLongMaxMillis() = runTest {
        val key = TestKey("owner-revalidated-overflow-age")
        val clock = FakeWallClock(now = Long.MIN_VALUE)
        var calls = 0
        val engine =
            KeyEngine(
                key,
                KeyId.from(key),
                ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        else -> FetcherResult.NotModified("e1")
                    }
                },
                InMemorySourceOfTruth(),
                InMemoryBookkeeper(),
                DefaultFreshnessValidator,
                clock,
                backgroundScope,
            )
        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        clock.now = Long.MAX_VALUE

        engine.stream(Freshness.MustBeFresh).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val age = assertIs<StoreResult.Revalidated>(awaitItem()).age
            assertEquals(Long.MAX_VALUE, age.inWholeMilliseconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun staleEpochAdvancedBeforeSubscription_isReplayedAfterPlanningEpoch() = runTest {
        val states = MutableStateFlow(KeyState.Initial)
        val planningEpoch = states.value.staleEpoch
        states.value = states.value.copy(staleEpoch = planningEpoch + 1L)

        assertEquals(
            planningEpoch + 1L,
            states.staleEpochsAfter(planningEpoch).first(),
        )
    }

    @Test
    fun mustBeFreshInvalidationDuringSuccessfulInitialFetch_isSatisfiedByCommit() = runTest {
        val key = TestKey("1")
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            firstStarted.complete(Unit)
                            firstGate.await()
                            FetcherResult.Success("v1")
                        }

                        2 -> {
                            secondStarted.complete(Unit)
                            FetcherResult.Success("v2")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = InMemorySourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        engine.stream(Freshness.MustBeFresh).test {
            assertIs<StoreResult.Loading>(awaitItem())
            firstStarted.await()
            engine.invalidate()
            firstGate.complete(Unit)

            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            testScheduler.runCurrent()
            assertEquals(1, calls)
            assertFalse(secondStarted.isCompleted)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mustBeFreshFastInitialCommit_doesNotMintAReplacementTicket() = runTest {
        val key = TestKey("fast")
        val sourceOfTruth = WriteStartedSourceOfTruth()
        val gate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("v$calls")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = gate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.MustBeFresh).testIn(backgroundScope)
            gate.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())
            gate.release()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            val data = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("v1", data.value)
            assertFalse(data.refreshing)
            testScheduler.runCurrent()
            assertEquals(1, calls)
            collector.expectNoEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cachedFastInitialCommit_afterInitialSnapshot_emitsLoadingBeforeData() = runTest {
        val key = TestKey("fast-after-snapshot")
        val sourceOfTruth = WriteStartedSourceOfTruth()
        val snapshotGate = InitialDeliveryGate().also { it.arm() }
        val readerDeliveryGate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("v1")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                afterInitialPlanningSnapshotTestGate = snapshotGate::awaitIfArmed,
                beforeReaderDeliveryTestGate = readerDeliveryGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            snapshotGate.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())
            snapshotGate.release()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            readerDeliveryGate.entered.await()
            collector.expectNoEvents()
            readerDeliveryGate.release()
            val data = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("v1", data.value)
            assertEquals(Origin.FETCHER, data.origin)
            assertFalse(data.refreshing)
            testScheduler.runCurrent()
            assertEquals(1, calls)
            collector.expectNoEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cachedFastInitialCommit_waitsForWriterCurrentRow() = runTest {
        val key = TestKey("cached-fast")
        val sourceOfTruth = WriteStartedSourceOfTruth()
        val initialGate = InitialDeliveryGate().also { it.arm() }
        val deliveryGate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("v1")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialGate::awaitIfArmed,
                beforeReaderDeliveryTestGate = deliveryGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            initialGate.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())
            initialGate.release()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            deliveryGate.entered.await()
            collector.expectNoEvents()
            deliveryGate.release()

            val data = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("v1", data.value)
            assertEquals(Origin.FETCHER, data.origin)
            assertFalse(data.refreshing)
            assertEquals(1, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun completedCommitObservedByInvalidate_reprocessesLatestCausalRowBeforeWatcherNoop() =
        runTest {
            val key = TestKey("commit-observed-by-invalidate")
            val sourceOfTruth = StartupRaceSourceOfTruth()
            val bookkeeper = GateSuccessBookkeeper()
            val outcomeDeliveryGate = InitialDeliveryGate()
            val thirdStarted = CompletableDeferred<Unit>()
            val releaseThird = CompletableDeferred<Unit>()
            var calls = 0
            val engine =
                KeyEngine(
                    key = key,
                    keyId = KeyId.from(key),
                    fetcher = ResultFetcher {
                        when (++calls) {
                            1 -> FetcherResult.Success("v1")
                            2 -> FetcherResult.Success("v2")
                            3 -> {
                                thirdStarted.complete(Unit)
                                releaseThird.await()
                                FetcherResult.Success("v3")
                            }

                            else -> error("unexpected fetch call $calls")
                        }
                    },
                    sot = sourceOfTruth,
                    bookkeeper = bookkeeper,
                    validator = DefaultFreshnessValidator,
                    wallClock = FakeWallClock(now = 0L),
                    engineScope = backgroundScope,
                    beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
                )

            assertEquals("v1", engine.get(Freshness.MustBeFresh))
            app.cash.turbine.turbineScope {
                val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                testScheduler.runCurrent()
                bookkeeper.gateNextSuccess()

                engine.invalidate()
                testScheduler.runCurrent()
                assertTrue(bookkeeper.successEntered.isCompleted)
                assertEquals("v2", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)

                outcomeDeliveryGate.arm()
                bookkeeper.releaseSuccess.complete(Unit)
                testScheduler.runCurrent()
                assertTrue(outcomeDeliveryGate.entered.isCompleted)
                engine.invalidate()
                testScheduler.runCurrent()

                assertTrue(thirdStarted.isCompleted)
                collector.expectNoEvents()
                outcomeDeliveryGate.release()
                releaseThird.complete(Unit)
                assertEquals("v3", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                assertEquals(3, calls)
                collector.cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun completedInitialDelete_doesNotLaunchAReplacement() = runTest {
        val key = TestKey("deleted-fast")
        val gate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Deleted
                },
                sot = InMemorySourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = gate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            gate.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            assertIs<FetchOutcome.Deleted>(ticket.outcome.await())
            gate.release()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            assertIs<StoreError.Missing>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            testScheduler.runCurrent()
            assertEquals(1, calls)
            collector.expectNoEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingDeletedOutcome_retiresMissingBeforeNewerSatisfiedRow() = runTest {
        val key = TestKey("pending-deleted-newer-row")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val bookkeeper = GateForgetBookkeeper()
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1")
                        2 -> {
                            deleteStarted.complete(Unit)
                            releaseDelete.await()
                            FetcherResult.Deleted
                        }

                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("recovered")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            bookkeeper.gateNextForget()
            engine.invalidate()
            deleteStarted.await()
            val deletedTicket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseDelete.complete(Unit)
            bookkeeper.forgetEntered.await()
            assertIs<FetchDisposition.Deleted>(deletedTicket.disposition.value)
            assertFalse(deletedTicket.outcome.isCompleted)

            sourceOfTruth.publish("external")
            testScheduler.runCurrent()
            collector.expectNoEvents()
            bookkeeper.releaseForget.complete(Unit)

            val delivered = mutableListOf<StoreResult<String>>()
            while (delivered.none { it is StoreResult.Data && it.value == "external" }) {
                delivered += collector.awaitItem()
            }
            val missingIndex =
                delivered.indexOfFirst {
                    it is StoreResult.Error && it.error is StoreError.Missing
                }
            val dataIndex =
                delivered.indexOfFirst {
                    it is StoreResult.Data && it.value == "external"
                }
            assertTrue(delivered.any { it is StoreResult.Loading })
            assertTrue(missingIndex >= 0)
            assertTrue(missingIndex < dataIndex)
            assertTrue(assertIs<StoreResult.Data<String>>(delivered[dataIndex]).refreshing)
            replacementStarted.await()

            releaseReplacement.complete(Unit)
            assertEquals(
                "recovered",
                assertIs<StoreResult.Data<String>>(collector.awaitItem()).value,
            )
            testScheduler.runCurrent()
            collector.expectNoEvents()
            assertEquals(3, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mustNotModifiedReaderReplay_doesNotLaunchAThirdFetch() = runTest {
        val key = TestKey("must-304-replay")
        val sourceOfTruth = ReplayEveryRowSourceOfTruth()
        val readerDeliveryGate = InitialDeliveryGate()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderDeliveryTestGate = readerDeliveryGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        engine.stream(Freshness.MustBeFresh).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals(Duration.ZERO, assertIs<StoreResult.Revalidated>(awaitItem()).age)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            // Cross the reader-delivery gate after the direct 304 completion so the assertion is
            // causally downstream of an actual equal-value replay, not merely its upstream emit.
            readerDeliveryGate.arm()
            sourceOfTruth.publish("v1")
            readerDeliveryGate.entered.await()
            expectNoEvents()
            readerDeliveryGate.release()
            testScheduler.runCurrent()
            assertEquals(2, calls)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingNotModified_sameValueReplayWaitsForWatcherDelivery() = runTest {
        val key = TestKey("pending-304-replay")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val bookkeeper = GateSuccessBookkeeper()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()
            bookkeeper.gateNextSuccess()

            engine.invalidate()
            bookkeeper.successEntered.await()
            sourceOfTruth.publish("v1")
            testScheduler.runCurrent()
            collector.expectNoEvents()

            bookkeeper.releaseSuccess.complete(Unit)
            assertEquals(
                Duration.ZERO,
                assertIs<StoreResult.Revalidated>(collector.awaitItem()).age,
            )
            assertEquals(2, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mappedReplayDeliveredBeforeCompletedNotModifiedWatcher_doesNotLaunchReplacement() =
        runTest {
            val key = TestKey("mapped-replay-before-304-watcher")
            val sourceOfTruth = StartupRaceSourceOfTruth()
            val readerDeliveryGate = InitialDeliveryGate()
            val fetchStarted = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()
            var calls = 0
            val engine =
                KeyEngine(
                    key = key,
                    keyId = KeyId.from(key),
                    fetcher = ResultFetcher {
                        when (++calls) {
                            1 -> {
                                fetchStarted.complete(Unit)
                                releaseFetch.await()
                                FetcherResult.NotModified(etag = "e1")
                            }

                            else -> error("unexpected fetch call $calls")
                        }
                    },
                    sot = sourceOfTruth,
                    bookkeeper = InMemoryBookkeeper(),
                    validator = DefaultFreshnessValidator,
                    wallClock = FakeWallClock(now = 0L),
                    engineScope = backgroundScope,
                    beforeReaderDeliveryTestGate = readerDeliveryGate::awaitIfArmed,
                )

            assertEquals("seed", engine.get(Freshness.LocalOnly))
            app.cash.turbine.turbineScope {
                val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals(
                    "seed",
                    assertIs<StoreResult.Data<String>>(observer.awaitItem()).value,
                )
                sourceOfTruth.liveReaderStarted.await()
                testScheduler.runCurrent()

                readerDeliveryGate.arm()
                val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
                val baseline = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("seed", baseline.value)
                assertTrue(baseline.isStale)
                assertTrue(baseline.refreshing)
                fetchStarted.await()
                val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
                readerDeliveryGate.entered.await()

                releaseFetch.complete(Unit)
                assertIs<FetchOutcome.Revalidated>(ticket.outcome.await())
                collector.expectNoEvents()
                readerDeliveryGate.release()

                assertEquals(
                    Duration.ZERO,
                    assertIs<StoreResult.Revalidated>(collector.awaitItem()).age,
                )
                assertEquals(1, calls)
                observer.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getNotModifiedInvalidatedDuringOutcomeTail_retriesBeforeReturning() = runTest {
        val key = TestKey("get-304-invalidated-tail")
        val outcomeDeliveryGate = InitialDeliveryGate()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        3 -> FetcherResult.Success("v3", etag = "e3")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = InMemorySourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        outcomeDeliveryGate.arm()
        val result =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.get(Freshness.MustBeFresh)
            }
        try {
            withTimeout(1_000) { outcomeDeliveryGate.entered.await() }
            withTimeout(1_000) { engine.invalidate() }
            outcomeDeliveryGate.release()

            assertEquals("v3", withTimeout(1_000) { result.await() })
            assertEquals(3, calls)
        } finally {
            outcomeDeliveryGate.release()
        }
    }

    @Test
    fun nonOwnerFailureHandoff_cannotLaunderAnotherTicketsRevalidation() = runTest {
        val key = TestKey("non-owner-304")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val bookkeeper = GateFailureBookkeeper()
        val outcomeDeliveryGate = InitialDeliveryGate()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.Error(IllegalStateException("offline"))
                        3 -> FetcherResult.NotModified(etag = "e2")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()
            bookkeeper.gateNextFailure()

            engine.invalidate()
            bookkeeper.failureEntered.await()
            outcomeDeliveryGate.arm()
            bookkeeper.releaseFailure.complete(Unit)
            outcomeDeliveryGate.entered.await()

            assertEquals("v1", engine.get(Freshness.MustBeFresh))
            outcomeDeliveryGate.release()
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            collector.expectNoEvents()
            assertEquals(3, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun passiveInitialRecapture_keepsPre304MemoryBaseline() = runTest {
        val key = TestKey("passive-initial-304")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val initialGate = InitialDeliveryGate().also { it.arm() }
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = clock,
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        clock.now = 10.minutes.inWholeMilliseconds
        app.cash.turbine.turbineScope {
            val passive = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            initialGate.entered.await()
            assertEquals("v1", engine.get(Freshness.MustBeFresh))
            initialGate.release()

            val baseline = assertIs<StoreResult.Data<String>>(passive.awaitItem())
            assertEquals("v1", baseline.value)
            assertEquals(10.minutes, baseline.age)
            assertEquals(2, calls)
            passive.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun foreignNotModifiedOwner_maxAgePlansFromVisibleBaselineAfterClockCrossing() = runTest {
        val key = TestKey("foreign-304-max-age")
        val sourceOfTruth = ReplayEveryRowSourceOfTruth()
        val clock = FakeWallClock(now = 0L)
        val bookkeeper = GateSuccessBookkeeper()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.NotModified(etag = "e3")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = clock,
                engineScope = backgroundScope,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val passive =
                engine.stream(Freshness.MaxAge(10.minutes)).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(passive.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            clock.now = 9.minutes.inWholeMilliseconds
            assertEquals("v1", engine.get(Freshness.MustBeFresh))
            passive.expectNoEvents()

            clock.now = 11.minutes.inWholeMilliseconds
            sourceOfTruth.publish("v1")
            assertIs<StoreResult.Loading>(passive.awaitItem())
            replacementStarted.await()
            passive.expectNoEvents()

            bookkeeper.gateNextSuccess()
            releaseReplacement.complete(Unit)
            bookkeeper.successEntered.await()
            passive.expectNoEvents()
            bookkeeper.releaseSuccess.complete(Unit)
            assertEquals(
                2.minutes,
                assertIs<StoreResult.Revalidated>(passive.awaitItem()).age,
            )
            testScheduler.runCurrent()
            passive.expectNoEvents()
            assertEquals(3, calls)
            passive.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun maxAgeLaunchWithheld_backwardClockDuringNotModifiedTail_staysLoading() = runTest {
        val key = TestKey("max-age-backward-clock-304")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val clock = FakeWallClock(now = 0L)
        val bookkeeper = GateSuccessBookkeeper()
        val initialGate = InitialDeliveryGate().also { it.arm() }
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = clock,
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        clock.now = 10.minutes.inWholeMilliseconds
        bookkeeper.gateNextSuccess()
        app.cash.turbine.turbineScope {
            val collector =
                engine.stream(Freshness.MaxAge(5.minutes)).testIn(backgroundScope)
            initialGate.entered.await()
            bookkeeper.successEntered.await()
            clock.now = 0L
            initialGate.release()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            collector.expectNoEvents()
            bookkeeper.releaseSuccess.complete(Unit)

            assertEquals(
                10.minutes,
                assertIs<StoreResult.Revalidated>(collector.awaitItem()).age,
            )
            assertEquals(2, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mappedDifferentRow_survivesForeignNotModifiedMetadataConvergence() = runTest {
        val key = TestKey("mapped-row-owner-convergence")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val readerDeliveryGate = InitialDeliveryGate()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    FetcherResult.NotModified(etag = "e$calls")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderDeliveryTestGate = readerDeliveryGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val passive = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(passive.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            readerDeliveryGate.arm()
            sourceOfTruth.publish("external")
            readerDeliveryGate.entered.await()
            assertEquals("external", engine.get(Freshness.MustBeFresh))

            readerDeliveryGate.release()
            val changed = assertIs<StoreResult.Data<String>>(passive.awaitItem())
            assertEquals("external", changed.value)
            assertEquals(Origin.SOT, changed.origin)
            passive.expectNoEvents()
            assertEquals(1, calls)
            passive.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readerFirstAuthoritativeAbsent_loadsBeforeGatedFailureOutcome() = runTest {
        val key = TestKey("reader-first-absent")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val outcomeDeliveryGate = InitialDeliveryGate()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1")
                        2 -> FetcherResult.Error(IllegalStateException("offline"))
                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("fresh")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            outcomeDeliveryGate.arm()
            engine.invalidate()
            outcomeDeliveryGate.entered.await()
            sourceOfTruth.publishAbsent()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            collector.expectNoEvents()
            outcomeDeliveryGate.release()
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            replacementStarted.await()
            releaseReplacement.complete(Unit)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            assertEquals(3, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun completedFailureRetainsDemandAcrossLateEqualReplay_forCachedAndStaleIfError() =
        runTest {
            app.cash.turbine.turbineScope {
                listOf(Freshness.CachedOrFetch, Freshness.StaleIfError).forEachIndexed {
                    index,
                    freshness,
                ->
                    val key = TestKey("failed-late-equal-replay-$index")
                    val mappingGate = InitialDeliveryGate().also { it.arm() }
                    val secondStarted = CompletableDeferred<Unit>()
                    val releaseSecond = CompletableDeferred<Unit>()
                    val thirdStarted = CompletableDeferred<Unit>()
                    val failure = IllegalStateException("offline-$index")
                    var calls = 0
                    val engine =
                        KeyEngine(
                            key = key,
                            keyId = KeyId.from(key),
                            fetcher = ResultFetcher {
                                when (++calls) {
                                    1 -> FetcherResult.Success("v1")
                                    2 -> {
                                        secondStarted.complete(Unit)
                                        releaseSecond.await()
                                        FetcherResult.Error(failure)
                                    }

                                    3 -> {
                                        thirdStarted.complete(Unit)
                                        FetcherResult.Success("unexpected")
                                    }

                                    else -> error("unexpected fetch call $calls")
                                }
                            },
                            sot = InMemorySourceOfTruth(),
                            bookkeeper = InMemoryBookkeeper(),
                            validator = DefaultFreshnessValidator,
                            wallClock = FakeWallClock(now = 0L),
                            engineScope = backgroundScope,
                            beforeReaderRecordMappingTestGate = mappingGate::awaitIfArmed,
                        )

                    assertEquals("v1", engine.get(Freshness.MustBeFresh))
                    engine.invalidate()
                    val collector = engine.stream(freshness).testIn(backgroundScope)
                    try {
                        val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                        assertEquals("v1", stale.value)
                        assertTrue(stale.isStale)
                        assertTrue(stale.refreshing)
                        secondStarted.await()
                        mappingGate.entered.await()

                        releaseSecond.complete(Unit)
                        val error = assertIs<StoreResult.Error>(collector.awaitItem())
                        assertTrue(error.servedStale)
                        assertEquals(2, calls)

                        mappingGate.release()
                        testScheduler.runCurrent()
                        assertFalse(
                            thirdStarted.isCompleted,
                            "equal late replay must not launch a third fetch for $freshness",
                        )
                        collector.expectNoEvents()
                    } finally {
                        releaseSecond.complete(Unit)
                        mappingGate.release()
                        collector.cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }

    @Test
    fun completedFailureRetainsDemandAcrossLateNullReplay() = runTest {
        val key = TestKey("failed-late-null-replay")
        val mappingGate = InitialDeliveryGate().also { it.arm() }
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            FetcherResult.Error(IllegalStateException("offline"))
                        }

                        2 -> {
                            secondStarted.complete(Unit)
                            FetcherResult.Success("unexpected")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = InMemorySourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfArmed,
            )

        engine.stream(Freshness.CachedOrFetch).test {
            assertIs<StoreResult.Loading>(awaitItem())
            firstStarted.await()
            mappingGate.entered.await()

            releaseFirst.complete(Unit)
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(awaitItem()).error)
            assertEquals(1, calls)

            mappingGate.release()
            testScheduler.runCurrent()
            assertFalse(
                secondStarted.isCompleted,
                "duplicate late absence must not launch a second fetch",
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun outerTicketCommittedAfterInitialOutcomeCheck_emitsLoadingBeforeData() = runTest {
        val key = TestKey("outer-ticket-final-classification")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val classificationGate = InitialDeliveryGate().also { it.arm() }
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("fresh")
                },
                sot = InMemorySourceOfTruth(),
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReplacementDispositionClassificationTestGate =
                    classificationGate::awaitIfArmed,
            )

        engine.stream(Freshness.CachedOrFetch).test {
            fetchStarted.await()
            classificationGate.entered.await()
            releaseFetch.complete(Unit)
            testScheduler.runCurrent()
            classificationGate.release()

            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun outerTicketCommittedWithBaselineAfterInitialOutcomeCheck_emitsLoading() = runTest {
        val key = TestKey("outer-ticket-committed-baseline")
        val sourceOfTruth = ReplayEveryRowSourceOfTruth()
        val mappingGate = InitialDeliveryGate()
        val classificationGate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("fresh")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfArmed,
                beforeReplacementDispositionClassificationTestGate =
                    classificationGate::awaitIfArmed,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()
            mappingGate.arm()

            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            classificationGate.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            mappingGate.entered.await()
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())
            classificationGate.release()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            mappingGate.release()
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            observer.cancelAndIgnoreRemainingEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun outerTicketCommittingAfterInitialOutcomeCheck_emitsServableBaseline() = runTest {
        val key = TestKey("outer-ticket-committing-baseline")
        val sourceOfTruth = GatedWriterReturnSourceOfTruth()
        val classificationGate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("fresh")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReplacementDispositionClassificationTestGate =
                    classificationGate::awaitIfArmed,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        engine.stream(Freshness.CachedOrFetch).test {
            classificationGate.entered.await()
            fetchStarted.await()
            releaseFetch.complete(Unit)
            sourceOfTruth.writeStarted.await()
            classificationGate.release()

            val baseline = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("seed", baseline.value)
            assertTrue(baseline.isStale)
            assertFalse(baseline.refreshing)
            expectNoEvents()

            sourceOfTruth.releaseWrite.complete(Unit)
            val committed = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("fresh", committed.value)
            assertEquals(Origin.FETCHER, committed.origin)
            assertFalse(committed.refreshing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun outerTicketRevalidatedAfterInitialOutcomeCheck_emitsBaselineThenFreshOwner() = runTest {
        val key = TestKey("outer-ticket-revalidated-baseline")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val classificationGate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.NotModified(etag = "e1")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReplacementDispositionClassificationTestGate =
                    classificationGate::awaitIfArmed,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        engine.stream(Freshness.CachedOrFetch).test {
            classificationGate.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            assertIs<FetchOutcome.Revalidated>(ticket.outcome.await())
            classificationGate.release()

            val baseline = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("seed", baseline.value)
            assertTrue(baseline.isStale)
            assertFalse(baseline.refreshing)

            assertEquals(Duration.ZERO, assertIs<StoreResult.Revalidated>(awaitItem()).age)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun outerTicketRevalidatedBeforeOutcomeTail_doesNotReserveReplacement() = runTest {
        val key = TestKey("outer-ticket-revalidated-pending-tail")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val bookkeeper = PreDelegateSuccessGateBookkeeper().also { it.gateNextSuccess() }
        val classificationGate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            fetchStarted.complete(Unit)
                            releaseFetch.await()
                            FetcherResult.NotModified(etag = "e1")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReplacementDispositionClassificationTestGate =
                    classificationGate::awaitIfArmed,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        engine.invalidate()
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            try {
                classificationGate.entered.await()
                fetchStarted.await()
                val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
                releaseFetch.complete(Unit)
                bookkeeper.successEntered.await()
                assertIs<FetchDisposition.Revalidated>(ticket.disposition.value)
                assertFalse(ticket.outcome.isCompleted)

                classificationGate.release()
                val baseline = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("seed", baseline.value)
                assertTrue(baseline.isStale)
                assertFalse(baseline.refreshing)
                assertEquals(1, calls)

                bookkeeper.releaseSuccess.complete(Unit)
                assertIs<FetchOutcome.Revalidated>(ticket.outcome.await())
                assertEquals(
                    Duration.ZERO,
                    assertIs<StoreResult.Revalidated>(collector.awaitItem()).age,
                )
                assertEquals(1, calls)
                collector.expectNoEvents()
                collector.cancelAndIgnoreRemainingEvents()
            } finally {
                classificationGate.release()
                releaseFetch.complete(Unit)
                bookkeeper.releaseSuccess.complete(Unit)
            }
        }
    }

    @Test
    fun cachedFastNotModified_emitsBaselineThenWatcherRefresh() = runTest {
        val key = TestKey("cached-fast-304")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val gate = InitialDeliveryGate().also { it.arm() }
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.NotModified(etag = "e1")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = gate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            gate.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            assertIs<FetchOutcome.Revalidated>(ticket.outcome.await())
            gate.release()

            val baseline = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("seed", baseline.value)
            assertEquals(Origin.SOT, baseline.origin)
            assertTrue(baseline.isStale)
            assertFalse(baseline.refreshing)

            assertEquals(
                Duration.ZERO,
                assertIs<StoreResult.Revalidated>(collector.awaitItem()).age,
            )
            testScheduler.runCurrent()
            assertEquals(1, calls)
            collector.expectNoEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cachedFastNotModifiedInvalidatedBeforeDelivery_reservesBeforeStaleData() = runTest {
        val key = TestKey("cached-fast-304-invalidated")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val initialGate = InitialDeliveryGate().also { it.arm() }
        val revalidationStarted = CompletableDeferred<Unit>()
        val releaseRevalidation = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            revalidationStarted.complete(Unit)
                            releaseRevalidation.await()
                            FetcherResult.NotModified(etag = "e1")
                        }

                        2 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("fresh", etag = "e2")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            initialGate.entered.await()
            revalidationStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseRevalidation.complete(Unit)
            assertIs<FetchOutcome.Revalidated>(ticket.outcome.await())
            engine.invalidate()
            initialGate.release()

            replacementStarted.await()
            val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("seed", stale.value)
            assertTrue(stale.isStale)
            assertTrue(stale.refreshing)

            releaseReplacement.complete(Unit)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            assertEquals(2, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mustNotModifiedInvalidatedBeforeDirectDelivery_launchesReplacement() = runTest {
        val key = TestKey("must-304-invalidated")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val outcomeDeliveryGate = InitialDeliveryGate()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("v3", etag = "e3")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        outcomeDeliveryGate.arm()
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.MustBeFresh).testIn(backgroundScope)
            try {
                val loading = withTimeoutOrNull(1_000) { collector.awaitItem() }
                assertIs<StoreResult.Loading>(loading, "initial Loading was not delivered")
                assertTrue(
                    withTimeoutOrNull(1_000) {
                        outcomeDeliveryGate.entered.await()
                        true
                    } == true,
                    "ticket outcome delivery gate was not reached",
                )
                withTimeout(1_000) { engine.invalidate() }
                outcomeDeliveryGate.release()

                withTimeout(1_000) { replacementStarted.await() }
                collector.expectNoEvents()
                releaseReplacement.complete(Unit)
                assertEquals(
                    "v3",
                    assertIs<StoreResult.Data<String>>(
                        withTimeout(1_000) { collector.awaitItem() },
                    ).value,
                )
                assertEquals(3, calls)
                collector.cancelAndIgnoreRemainingEvents()
            } finally {
                outcomeDeliveryGate.release()
                releaseReplacement.complete(Unit)
            }
        }
    }

    @Test
    fun mustNotModifiedInvalidatedBeforeDirectDelivery_replacementFailureIsTerminal() =
        runTest {
            val key = TestKey("must-304-invalidated-failure")
            val sourceOfTruth = StartupRaceSourceOfTruth()
            val outcomeDeliveryGate = InitialDeliveryGate()
            var calls = 0
            val engine =
                KeyEngine(
                    key = key,
                    keyId = KeyId.from(key),
                    fetcher = ResultFetcher {
                        when (++calls) {
                            1 -> FetcherResult.Success("v1", etag = "e1")
                            2 -> FetcherResult.NotModified(etag = "e2")
                            3 -> FetcherResult.Error(IllegalStateException("offline"))
                            else -> error("unexpected fetch call $calls")
                        }
                    },
                    sot = sourceOfTruth,
                    bookkeeper = InMemoryBookkeeper(),
                    validator = DefaultFreshnessValidator,
                    wallClock = FakeWallClock(now = 0L),
                    engineScope = backgroundScope,
                    beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
                )

            assertEquals("v1", engine.get(Freshness.MustBeFresh))
            outcomeDeliveryGate.arm()
            engine.stream(Freshness.MustBeFresh).test {
                try {
                    val loading = withTimeoutOrNull(1_000) { awaitItem() }
                    assertIs<StoreResult.Loading>(loading, "initial Loading was not delivered")
                    assertTrue(
                        withTimeoutOrNull(1_000) {
                            outcomeDeliveryGate.entered.await()
                            true
                        } == true,
                        "ticket outcome delivery gate was not reached",
                    )
                    withTimeout(1_000) { engine.invalidate() }
                    outcomeDeliveryGate.release()

                    assertIs<StoreError.Fetch>(
                        assertIs<StoreResult.Error>(withTimeout(1_000) { awaitItem() }).error,
                    )
                    awaitComplete()
                    assertEquals(3, calls)
                } finally {
                    outcomeDeliveryGate.release()
                }
            }
        }

    @Test
    fun initialDeliveryGate_equalContentNewRevisionIsNeverRestampedMemory() = runTest {
        val key = TestKey("memory-race")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val gate = InitialDeliveryGate()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { error("fetch must not run") },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = gate::awaitIfArmed,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            gate.arm()

            val raced = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            gate.entered.await()
            sourceOfTruth.publish("other")
            assertEquals("other", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.publish("seed")
            val replacement = assertIs<StoreResult.Data<String>>(observer.awaitItem())
            assertEquals("seed", replacement.value)
            assertEquals(Origin.SOT, replacement.origin)

            gate.release()
            val first = assertIs<StoreResult.Data<String>>(raced.awaitItem())
            assertEquals("seed", first.value)
            assertEquals(Origin.SOT, first.origin)
            observer.cancelAndIgnoreRemainingEvents()
            raced.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedInitialTicket_replansAResidenceThatChangedBeforeFinalDelivery() = runTest {
        val key = TestKey("failed-race")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val gate = InitialDeliveryGate()
        val bookkeeper = FailureSignallingBookkeeper()
        val secondStarted = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Error(IllegalStateException("offline"))
                        2 -> {
                            secondStarted.complete(Unit)
                            FetcherResult.Success("fresh")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = gate::awaitIfArmed,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            gate.arm()

            val raced = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            gate.entered.await()
            bookkeeper.failureRecorded.await()
            sourceOfTruth.publish("external")
            assertEquals("external", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            gate.release()

            val external = assertIs<StoreResult.Data<String>>(raced.awaitItem())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            assertTrue(external.refreshing)
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(raced.awaitItem()).error)
            secondStarted.await()
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(raced.awaitItem()).value)
            assertEquals(2, calls)
            observer.cancelAndIgnoreRemainingEvents()
            raced.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fastReplacementCommit_handsOffNewerRowBeforeSavedFailure() = runTest {
        val key = TestKey("fast-replacement-commit")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val initialGate = InitialDeliveryGate()
        val replacementDispositionGate = InitialDeliveryGate().also { it.arm() }
        val bookkeeper = GateSuccessBookkeeper()
        val recoveryStarted = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Error(IllegalStateException("old failure"))
                        2 -> FetcherResult.Success("replacement")
                        3 -> {
                            recoveryStarted.complete(Unit)
                            releaseRecovery.await()
                            FetcherResult.Success("recovered")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialGate::awaitIfArmed,
                beforeReplacementDispositionClassificationTestGate =
                    replacementDispositionGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            initialGate.arm()
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            initialGate.entered.await()
            bookkeeper.failureRecorded.await()
            sourceOfTruth.publish("external-1")
            assertEquals(
                "external-1",
                assertIs<StoreResult.Data<String>>(observer.awaitItem()).value,
            )

            bookkeeper.gateNextSuccess()
            initialGate.release()
            replacementDispositionGate.entered.await()
            bookkeeper.successEntered.await()
            sourceOfTruth.publish("external-2")
            while (true) {
                val observed = assertIs<StoreResult.Data<String>>(observer.awaitItem())
                if (observed.value == "external-2") break
            }
            replacementDispositionGate.release()
            collector.expectNoEvents()

            bookkeeper.releaseSuccess.complete(Unit)
            val handedOff = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("external-2", handedOff.value)
            assertTrue(handedOff.refreshing)
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            recoveryStarted.await()

            releaseRecovery.complete(Unit)
            assertEquals(
                "recovered",
                assertIs<StoreResult.Data<String>>(collector.awaitItem()).value,
            )
            assertEquals(3, calls)
            observer.cancelAndIgnoreRemainingEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fastReplacementNotModified_deliversBaselineThenOwnerBeforeSavedFailure() = runTest {
        val key = TestKey("fast-replacement-304")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val initialGate = InitialDeliveryGate()
        val replacementDispositionGate = InitialDeliveryGate().also { it.arm() }
        val bookkeeper = GateSuccessBookkeeper()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Error(IllegalStateException("old failure"))
                        2 -> FetcherResult.NotModified(etag = "e2")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialGate::awaitIfArmed,
                beforeReplacementDispositionClassificationTestGate =
                    replacementDispositionGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            initialGate.arm()
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            initialGate.entered.await()
            bookkeeper.failureRecorded.await()
            sourceOfTruth.publish("external")
            assertEquals(
                "external",
                assertIs<StoreResult.Data<String>>(observer.awaitItem()).value,
            )

            bookkeeper.gateNextSuccess()
            initialGate.release()
            replacementDispositionGate.entered.await()
            bookkeeper.successEntered.await()
            replacementDispositionGate.release()

            val baseline = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("external", baseline.value)
            assertEquals(Origin.SOT, baseline.origin)
            assertTrue(baseline.isStale)
            collector.expectNoEvents()

            bookkeeper.releaseSuccess.complete(Unit)
            assertEquals(
                Duration.ZERO,
                assertIs<StoreResult.Revalidated>(collector.awaitItem()).age,
            )
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            assertEquals(2, calls)
            observer.cancelAndIgnoreRemainingEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingInitialCommitTail_defersNewerRowUntilReplacementIsReserved() = runTest {
        val key = TestKey("pending-initial-tail")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val initialGate = InitialDeliveryGate()
        val bookkeeper = GateSuccessBookkeeper()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("candidate")
                        2 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("fresh")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialGate::awaitIfArmed,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()
            bookkeeper.gateNextSuccess()
            initialGate.arm()

            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            initialGate.entered.await()
            bookkeeper.successEntered.await()
            sourceOfTruth.publish("external")
            while (true) {
                val item = observer.awaitItem()
                if (item is StoreResult.Data && item.value == "external") break
            }
            initialGate.release()

            collector.expectNoEvents()

            bookkeeper.releaseSuccess.complete(Unit)
            replacementStarted.await()
            val handedOff = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("external", handedOff.value)
            assertTrue(handedOff.refreshing)
            releaseReplacement.complete(Unit)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            assertEquals(2, calls)
            observer.cancelAndIgnoreRemainingEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun joinedOlderTicketFailure_replansTheCollectorsNewerResidence() = runTest {
        val key = TestKey("joined-failure")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val deliveryGate = InitialDeliveryGate()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1")
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                            FetcherResult.Error(IllegalStateException("offline"))
                        }

                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("recovered")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderDeliveryTestGate = deliveryGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val owner =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { engine.get(Freshness.MustBeFresh) }
                }
            secondStarted.await()
            val failedTicket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            sourceOfTruth.publish("external-1")

            val external = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("external-1", external.value)
            assertTrue(external.refreshing)
            deliveryGate.arm()
            sourceOfTruth.publish("external-2")
            deliveryGate.entered.await()
            releaseSecond.complete(Unit)

            assertIs<FetchOutcome.Failed>(failedTicket.outcome.await())
            assertTrue(owner.await().isFailure)
            deliveryGate.release()

            val handedOff = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("external-2", handedOff.value)
            assertTrue(handedOff.refreshing)
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            replacementStarted.await()
            releaseReplacement.complete(Unit)
            assertEquals(
                "recovered",
                assertIs<StoreResult.Data<String>>(collector.awaitItem()).value,
            )
            assertEquals(3, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun postInitialMustFailure_replansANewerResidence() = runTest {
        val key = TestKey("live-must-failure")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1")
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                            FetcherResult.Error(IllegalStateException("offline"))
                        }

                        3 -> {
                            replacementStarted.complete(Unit)
                            FetcherResult.Success("recovered")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        app.cash.turbine.turbineScope {
            val must = engine.stream(Freshness.MustBeFresh).testIn(backgroundScope)
            assertIs<StoreResult.Loading>(must.awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(must.awaitItem()).value)
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            engine.invalidate()
            assertIs<StoreResult.Loading>(must.awaitItem())
            secondStarted.await()
            sourceOfTruth.publish("external")
            assertEquals(
                "external",
                assertIs<StoreResult.Data<String>>(observer.awaitItem()).value,
            )
            releaseSecond.complete(Unit)

            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(must.awaitItem()).error)
            replacementStarted.await()
            assertEquals("recovered", assertIs<StoreResult.Data<String>>(must.awaitItem()).value)
            assertEquals(3, calls)
            observer.cancelAndIgnoreRemainingEvents()
            must.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun replacementFailure_reportsTheStaleValueThatRemainedVisible() = runTest {
        val key = TestKey("replacement-stale")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1")
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                            FetcherResult.Error(IllegalStateException("first failure"))
                        }

                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Error(IllegalStateException("second failure"))
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val owner =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { engine.get(Freshness.MustBeFresh) }
                }
            secondStarted.await()
            sourceOfTruth.publish("external")
            val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("external", stale.value)
            assertTrue(stale.isStale)
            assertTrue(stale.refreshing)

            releaseSecond.complete(Unit)
            assertTrue(owner.await().isFailure)
            val firstFailure = assertIs<StoreResult.Error>(collector.awaitItem())
            assertTrue(firstFailure.servedStale)
            replacementStarted.await()
            releaseReplacement.complete(Unit)

            val secondFailure = assertIs<StoreResult.Error>(collector.awaitItem())
            assertTrue(secondFailure.servedStale)
            assertEquals(3, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingFailureTail_handsOffNewerRowBeforeSurfacingError() = runTest {
        val key = TestKey("pending-failure-tail")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val bookkeeper = GateFailureBookkeeper()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1")
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                            FetcherResult.Error(IllegalStateException("offline"))
                        }

                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("fresh")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()
            bookkeeper.gateNextFailure()

            val owner =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { engine.get(Freshness.MustBeFresh) }
                }
            secondStarted.await()
            sourceOfTruth.publish("external-1")
            assertEquals(
                "external-1",
                assertIs<StoreResult.Data<String>>(collector.awaitItem()).value,
            )
            releaseSecond.complete(Unit)
            bookkeeper.failureEntered.await()

            sourceOfTruth.publish("external-2")
            testScheduler.runCurrent()
            collector.expectNoEvents()
            bookkeeper.releaseFailure.complete(Unit)
            assertTrue(owner.await().isFailure)

            val handedOff = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("external-2", handedOff.value)
            assertTrue(handedOff.refreshing)
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            replacementStarted.await()
            releaseReplacement.complete(Unit)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            assertEquals(3, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun completedFailureTail_queuedAuthoritativeAbsentLoadsBeforeSavedError() = runTest {
        val key = TestKey("completed-failure-absent")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val bookkeeper = GateFailureBookkeeper()
        val readerDeliveryGate = InitialDeliveryGate()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1")
                        2 -> FetcherResult.Error(IllegalStateException("offline"))
                        3 -> {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                            FetcherResult.Success("fresh")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderDeliveryTestGate = readerDeliveryGate::awaitIfArmed,
            )

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()
            bookkeeper.gateNextFailure()

            engine.invalidate()
            bookkeeper.failureEntered.await()
            readerDeliveryGate.arm()
            sourceOfTruth.publish("queued-row")
            readerDeliveryGate.entered.await()

            bookkeeper.releaseFailure.complete(Unit)
            testScheduler.runCurrent()
            sourceOfTruth.publishAbsent()
            testScheduler.runCurrent()
            assertTrue(runCatching { engine.get(Freshness.LocalOnly) }.isFailure)
            readerDeliveryGate.release()

            assertIs<StoreResult.Loading>(collector.awaitItem())
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(collector.awaitItem()).error)
            replacementStarted.await()
            releaseReplacement.complete(Unit)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            assertEquals(3, calls)
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun coldPreWriteAbsent_cannotBecomePostCutoffAuthority() = runTest {
        val key = TestKey("cold-pre-write-absent")
        val sourceOfTruth = HeldColdObservationSourceOfTruth()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val releaseUnexpectedFetch = CompletableDeferred<Unit>()
        val outcomeDeliveryGate = InitialDeliveryGate().also { it.arm() }
        val planningContexts = mutableListOf<FreshnessContext>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            fetchStarted.complete(Unit)
                            releaseFirstFetch.await()
                            FetcherResult.Success("fresh")
                        }

                        2 -> {
                            releaseUnexpectedFetch.await()
                            FetcherResult.Success("unexpected")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator =
                    object : FreshnessValidator {
                        override fun plan(context: FreshnessContext): FetchPlan {
                            planningContexts += context
                            return DefaultFreshnessValidator.plan(context)
                        }
                    },
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertIs<StoreResult.Loading>(collector.awaitItem())
            sourceOfTruth.coldObservationHeld.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket

            releaseFirstFetch.complete(Unit)
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())
            outcomeDeliveryGate.entered.await()
            testScheduler.advanceTimeBy(
                (COLD_OBSERVATION_HOLD + WRITER_CURRENT_DELIVERY_GAP).inWholeMilliseconds,
            )
            testScheduler.runCurrent()

            val data = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            val committedContext = planningContexts.last { it.hasResidentValue }
            assertEquals(
                ColdPathContractObservation(
                    origin = Origin.FETCHER,
                    isStale = false,
                    refreshing = false,
                    hasMeta = true,
                    fetchCalls = 1,
                ),
                ColdPathContractObservation(
                    origin = data.origin,
                    isStale = data.isStale,
                    refreshing = data.refreshing,
                    hasMeta = committedContext.meta != null,
                    fetchCalls = calls,
                ),
            )

            collector.cancelAndIgnoreRemainingEvents()
            outcomeDeliveryGate.release()
            releaseUnexpectedFetch.complete(Unit)
        }
    }

    @Test
    fun coldPreWriteAbsent_twoCollectorsCannotLaunchMirrorFetch() = runTest {
        val key = TestKey("cold-pre-write-absent-two-collectors")
        val sourceOfTruth = HeldColdObservationSourceOfTruth(immediateReaderCalls = 2)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val releaseUnexpectedFetch = CompletableDeferred<Unit>()
        val initialDeliveryGate = SequencedGate()
        val collectorAInitial = initialDeliveryGate.gateNext()
        val collectorBInitial = initialDeliveryGate.gateNext()
        val outcomeDeliveryGate = SequencedGate()
        val collectorAOutcome = outcomeDeliveryGate.gateNext()
        val collectorBOutcome = outcomeDeliveryGate.gateNext()
        val planningContexts = mutableListOf<FreshnessContext>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            fetchStarted.complete(Unit)
                            releaseFirstFetch.await()
                            FetcherResult.Success("fresh")
                        }

                        2 -> {
                            releaseUnexpectedFetch.await()
                            FetcherResult.Success("unexpected")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator =
                    object : FreshnessValidator {
                        override fun plan(context: FreshnessContext): FetchPlan {
                            planningContexts += context
                            return DefaultFreshnessValidator.plan(context)
                        }
                },
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeInitialDeliveryTestGate = initialDeliveryGate::awaitIfQueued,
                beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfQueued,
            )

        app.cash.turbine.turbineScope {
            val collectorA = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            val collectorB = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            collectorAInitial.entered.await()
            collectorBInitial.entered.await()
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            assertEquals(1, calls)
            assertFalse(sourceOfTruth.coldObservationHeld.isCompleted)
            collectorAInitial.release.complete(Unit)
            collectorBInitial.release.complete(Unit)
            assertIs<StoreResult.Loading>(collectorA.awaitItem())
            assertIs<StoreResult.Loading>(collectorB.awaitItem())
            sourceOfTruth.coldObservationHeld.await()

            releaseFirstFetch.complete(Unit)
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())
            collectorAOutcome.entered.await()
            collectorBOutcome.entered.await()
            testScheduler.advanceTimeBy(
                (COLD_OBSERVATION_HOLD + WRITER_CURRENT_DELIVERY_GAP).inWholeMilliseconds,
            )
            testScheduler.runCurrent()

            val data =
                listOf(
                    assertIs<StoreResult.Data<String>>(collectorA.awaitItem()),
                    assertIs<StoreResult.Data<String>>(collectorB.awaitItem()),
                )
            val committedContext = planningContexts.last { it.hasResidentValue }
            data.forEach { result ->
                assertEquals(
                    ColdPathContractObservation(
                        origin = Origin.FETCHER,
                        isStale = false,
                        refreshing = false,
                        hasMeta = true,
                        fetchCalls = 1,
                    ),
                    ColdPathContractObservation(
                        origin = result.origin,
                        isStale = result.isStale,
                        refreshing = result.refreshing,
                        hasMeta = committedContext.meta != null,
                        fetchCalls = calls,
                    ),
                )
            }

            collectorA.cancelAndIgnoreRemainingEvents()
            collectorB.cancelAndIgnoreRemainingEvents()
            collectorAOutcome.release.complete(Unit)
            collectorBOutcome.release.complete(Unit)
            releaseUnexpectedFetch.complete(Unit)
        }
    }

    @Test
    fun successorWrite_cannotRetireQueuedPredecessorEchoFence() = runTest {
        val key = TestKey("successor-write-predecessor-echo")
        val sourceOfTruth = ConsecutiveWriteEchoSourceOfTruth()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val outcomeDeliveryGate = InitialDeliveryGate().also { it.arm() }
        val planningContexts = mutableListOf<FreshnessContext>()
        val unexpectedFetchStarted = CompletableDeferred<Unit>()
        val releaseUnexpectedFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            fetchStarted.complete(Unit)
                            releaseFirstFetch.await()
                            FetcherResult.Success("first")
                        }

                        2 -> {
                            unexpectedFetchStarted.complete(Unit)
                            releaseUnexpectedFetch.await()
                            FetcherResult.Success("unexpected")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator =
                    object : FreshnessValidator {
                        override fun plan(context: FreshnessContext): FetchPlan {
                            planningContexts += context
                            return DefaultFreshnessValidator.plan(context)
                        }
                    },
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeTicketOutcomeDeliveryTestGate = outcomeDeliveryGate::awaitIfArmed,
            )

        app.cash.turbine.turbineScope {
            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertIs<StoreResult.Loading>(collector.awaitItem())
            fetchStarted.await()
            sourceOfTruth.liveReaderStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFirstFetch.complete(Unit)
            sourceOfTruth.firstEchoHeld.await()
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())
            outcomeDeliveryGate.entered.await()

            val successor = backgroundScope.async { engine.applyWrite("second") }
            try {
                sourceOfTruth.secondWriteStarted.await()
                sourceOfTruth.releaseFirstEcho.complete(Unit)
                testScheduler.runCurrent()

                val first = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                val committedContext = planningContexts.last { it.hasResidentValue }
                assertEquals(
                    ColdPathContractObservation(
                        origin = Origin.FETCHER,
                        isStale = false,
                        refreshing = false,
                        hasMeta = true,
                        fetchCalls = 1,
                    ),
                    ColdPathContractObservation(
                        origin = first.origin,
                        isStale = first.isStale,
                        refreshing = first.refreshing,
                        hasMeta = committedContext.meta != null,
                        fetchCalls = calls,
                    ),
                )
                assertFalse(unexpectedFetchStarted.isCompleted)
            } finally {
                sourceOfTruth.releaseFirstEcho.complete(Unit)
                sourceOfTruth.releaseSecondWriteReturn.complete(Unit)
                sourceOfTruth.releaseSecondEcho.complete(Unit)
                outcomeDeliveryGate.release()
                releaseUnexpectedFetch.complete(Unit)
            }
            successor.await()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedSuccessorWrite_cannotStripCommittedPredecessorEnvelope() = runTest {
        val key = TestKey("failed-successor-preserves-predecessor")
        val sourceOfTruth = FailingSuccessorSourceOfTruth()
        val mappingGate = SequencedGate()
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val unexpectedFetchStarted = CompletableDeferred<Unit>()
        val releaseUnexpectedFetch = CompletableDeferred<Unit>()
        val planningContexts = mutableListOf<FreshnessContext>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            firstFetchStarted.complete(Unit)
                            releaseFirstFetch.await()
                            FetcherResult.Success("first")
                        }

                        2 -> {
                            unexpectedFetchStarted.complete(Unit)
                            releaseUnexpectedFetch.await()
                            FetcherResult.Success("unexpected")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator =
                    object : FreshnessValidator {
                        override fun plan(context: FreshnessContext): FetchPlan {
                            planningContexts += context
                            return DefaultFreshnessValidator.plan(context)
                        }
                    },
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        app.cash.turbine.turbineScope {
            val owner = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            try {
                assertIs<StoreResult.Loading>(owner.awaitItem())
                firstFetchStarted.await()
                sourceOfTruth.liveReaderStarted.await()
                testScheduler.runCurrent()
                val firstTicket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
                releaseFirstFetch.complete(Unit)
                withTimeout(3_000L) { sourceOfTruth.firstNotificationQueued.await() }
                assertIs<FetchOutcome.Committed>(
                    withTimeout(3_000L) { firstTicket.outcome.await() },
                )

                val successorExact = mappingGate.gateNext()
                val rollback = mappingGate.gateNext()
                try {
                    val successor =
                        backgroundScope.async {
                            runCatching { engine.applyWrite("second") }
                        }
                    withTimeout(3_000L) { successorExact.entered.await() }
                    sourceOfTruth.releaseRollback.complete(Unit)
                    withTimeout(3_000L) { sourceOfTruth.rollbackRawCaptured.await() }

                    successorExact.release.complete(Unit)
                    withTimeout(3_000L) { rollback.entered.await() }
                    rollback.release.complete(Unit)
                    testScheduler.runCurrent()
                    sourceOfTruth.releaseFailure.complete(Unit)
                    assertTrue(withTimeout(3_000L) { successor.await() }.isFailure)
                    testScheduler.runCurrent()

                    val retained = assertIs<StoreResult.Data<String>>(owner.awaitItem())
                    val retainedContext = planningContexts.last { it.hasResidentValue }
                    assertEquals(
                        ColdPathContractObservation(
                            origin = Origin.FETCHER,
                            isStale = false,
                            refreshing = false,
                            hasMeta = true,
                            fetchCalls = 1,
                        ),
                        ColdPathContractObservation(
                            origin = retained.origin,
                            isStale = retained.isStale,
                            refreshing = retained.refreshing,
                            hasMeta = retainedContext.meta != null,
                            fetchCalls = calls,
                        ),
                    )
                    assertFalse(unexpectedFetchStarted.isCompleted)
                } finally {
                    successorExact.release.complete(Unit)
                    rollback.release.complete(Unit)
                    sourceOfTruth.releaseRollback.complete(Unit)
                    sourceOfTruth.releaseFailure.complete(Unit)
                    releaseUnexpectedFetch.complete(Unit)
                }
            } finally {
                sourceOfTruth.releaseFirstNotification.complete(Unit)
                sourceOfTruth.releaseRollback.complete(Unit)
                releaseFirstFetch.complete(Unit)
                releaseUnexpectedFetch.complete(Unit)
                owner.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun replacementReaderSession_retiresUnresolvedPredecessorFence() = runTest {
        val key = TestKey("replacement-retires-predecessor-fence")
        val sourceOfTruth = ConsecutiveWriteEchoSourceOfTruth()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("first")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        app.cash.turbine.turbineScope {
            val owner = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertIs<StoreResult.Loading>(owner.awaitItem())
            fetchStarted.await()
            sourceOfTruth.liveReaderStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            sourceOfTruth.firstEchoHeld.await()
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())

            owner.cancelAndIgnoreRemainingEvents()
            testScheduler.advanceTimeBy(READER_PIPELINE_GRACE_MILLIS + 1L)
            testScheduler.runCurrent()

            val replacement = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            sourceOfTruth.replacementReaderStarted.await()
            sourceOfTruth.publishExternal("external")
            var external: StoreResult.Data<String>? = null
            while (external == null) {
                val item = replacement.awaitItem()
                if (item is StoreResult.Data && item.value == "external") external = item
            }
            assertEquals(Origin.SOT, checkNotNull(external).origin)
            assertEquals(1, calls)

            replacement.cancelAndIgnoreRemainingEvents()
            sourceOfTruth.releaseFirstEcho.complete(Unit)
        }
    }

    @Test
    fun olderPreStampRow_cannotOverwriteANewerWriterObservation() = runTest {
        val key = TestKey("older-pre-stamp-row")
        val sourceOfTruth = ReplayEveryRowSourceOfTruth()
        val mappingGate = SequencedGate()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("candidate")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val preStamp = mappingGate.gateNext()
            val writerCurrent = mappingGate.gateNext()
            sourceOfTruth.publish("older")
            preStamp.entered.await()

            val collector = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("seed", stale.value)
            fetchStarted.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
            releaseFetch.complete(Unit)
            assertIs<FetchOutcome.Committed>(ticket.outcome.await())

            preStamp.release.complete(Unit)
            writerCurrent.entered.await()
            collector.expectNoEvents()
            writerCurrent.release.complete(Unit)

            val committed = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("candidate", committed.value)
            assertEquals(Origin.FETCHER, committed.origin)
            assertFalse(committed.refreshing)
            assertEquals(1, calls)
            observer.cancelAndIgnoreRemainingEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun delayedExactWriterRow_cannotRegressANewerSameValueRevalidation() = runTest {
        val key = TestKey("delayed-exact-after-revalidation")
        val sourceOfTruth = SingleWriterCurrentSourceOfTruth()
        val mappingGate = SequencedGate()
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("fresh")
                        2 -> FetcherResult.NotModified(etag = "e2")
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = clock,
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val delayedExact = mappingGate.gateNext()
            val first = async { engine.get(Freshness.MustBeFresh) }
            delayedExact.entered.await()
            sourceOfTruth.releaseWriteReturn.complete(Unit)
            assertEquals("fresh", first.await())

            clock.now = 10.minutes.inWholeMilliseconds
            engine.invalidate()
            assertEquals("fresh", engine.get(Freshness.MustBeFresh))
            assertEquals(2, calls)

            delayedExact.release.complete(Unit)
            testScheduler.runCurrent()
            assertEquals("fresh", engine.get(Freshness.MaxAge(notOlderThan = 5.minutes)))
            assertEquals(2, calls, "delayed exact row must not restore the older metadata revision")
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun delayedExactWriterRow_afterConfirmFresh_reusesCurrentEnvelopeForProjection() = runTest {
        val key = TestKey("delayed-exact-after-confirm-fresh")
        val sourceOfTruth = DelayedWriterEchoSourceOfTruth()
        val exactRowMapped = CompletableDeferred<Unit>()
        var observeExactRow = false
        var fetchCalls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    fetchCalls += 1
                    FetcherResult.Success("unexpected")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = {
                    if (observeExactRow) exactRowMapped.complete(Unit)
                },
                overlay = PassThroughOverlay,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            engine.applyWrite("echo")
            engine.confirmFresh(etag = "confirmed")
            sourceOfTruth.writerEchoHeld.await()

            val delayed =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    observer.awaitItem()
                }
            observeExactRow = true
            sourceOfTruth.releaseWriterEcho.complete(Unit)
            exactRowMapped.await()
            testScheduler.runCurrent()

            assertTrue(
                delayed.isCompleted,
                "the delayed exact row must authorize the metadata-refreshed projection",
            )
            val echo = assertIs<StoreResult.Data<String>>(delayed.await())
            assertEquals("echo", echo.value)
            assertEquals(Origin.SOT, echo.origin)
            assertEquals("echo", engine.get(Freshness.MaxAge(notOlderThan = 5.minutes)))
            assertEquals(0, fetchCalls)
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun committedFence_withoutWriterEcho_restartsReaderForCurrentAuthority() = runTest {
        val key = TestKey("committed-fence-without-writer-echo")
        val sourceOfTruth = ConflatedWriterEchoSourceOfTruth()
        val fencedRowMapped = CompletableDeferred<Unit>()
        var observeFencedRow = false
        var fetchCalls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    fetchCalls += 1
                    FetcherResult.Success("unexpected")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = {
                    if (observeFencedRow) fencedRowMapped.complete(Unit)
                },
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderHeld.await()
            testScheduler.runCurrent()
            val readerCallsBeforeRecovery = sourceOfTruth.readerCalls

            engine.applyWrite("candidate")
            sourceOfTruth.publishExternal("external")
            val delayed =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    observer.awaitItem()
                }
            observeFencedRow = true
            sourceOfTruth.releaseLiveReader.complete(Unit)
            fencedRowMapped.await()
            testScheduler.runCurrent()

            assertTrue(
                sourceOfTruth.readerCalls > readerCallsBeforeRecovery,
                "a fenced mismatch must replace the reader before accepting current authority",
            )
            assertTrue(
                delayed.isCompleted,
                "the replacement reader must deliver its causally post-return current row",
            )
            val external = assertIs<StoreResult.Data<String>>(delayed.await())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            assertEquals(0, fetchCalls)
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun writerCurrentRaw_survivesGraceCancellationAfterMismatchedActiveRowMaps() = runTest {
        val key = TestKey("writer-current-after-grace")
        val sourceOfTruth = InterleavedWriteSourceOfTruth()
        val mappingGate = SequencedGate()
        val releaseFetch = CompletableDeferred<Unit>()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    releaseFetch.await()
                    FetcherResult.Success("fresh")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val mismatchedRow = mappingGate.gateNext()
            val writerCurrentRow = mappingGate.gateNext()
            val owner =
                engine.stream(Freshness.MaxAge(notOlderThan = 5.minutes))
                    .testIn(backgroundScope)
            assertIs<StoreResult.Loading>(owner.awaitItem())
            releaseFetch.complete(Unit)

            mismatchedRow.entered.await()
            val ticket = checkNotNull(engine.state.value.attribution).owner
            mismatchedRow.release.complete(Unit)
            val mismatched = assertIs<StoreResult.Data<String>>(observer.awaitItem())
            assertEquals("mismatched", mismatched.value)
            assertEquals(Origin.SOT, mismatched.origin)

            sourceOfTruth.releaseWriterCurrent.complete(Unit)
            writerCurrentRow.entered.await()
            sourceOfTruth.writerCurrentPublished.await()
            owner.cancelAndIgnoreRemainingEvents()
            observer.cancelAndIgnoreRemainingEvents()
            testScheduler.advanceTimeBy(READER_PIPELINE_GRACE_MILLIS + 1L)
            testScheduler.runCurrent()

            sourceOfTruth.releaseWriteReturn.complete(Unit)
            ticket.disposition.first { it is FetchDisposition.Committed }
            assertEquals("fresh", engine.get(Freshness.LocalOnly))
            writerCurrentRow.release.complete(Unit)
        }
    }

    @Test
    fun matchingThenOtherThenFinalMatching_convergesOnlyTheFinalWriterRow() = runTest {
        val key = TestKey("matching-other-final-matching")
        val sourceOfTruth = MatchingOtherMatchingSourceOfTruth()
        val mappingGate = SequencedGate()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    calls += 1
                    FetcherResult.Success("fresh")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val firstMatching = mappingGate.gateNext()
            val owner =
                engine.stream(Freshness.MaxAge(notOlderThan = 5.minutes))
                    .testIn(backgroundScope)
            assertIs<StoreResult.Loading>(owner.awaitItem())
            firstMatching.entered.await()
            sourceOfTruth.finalMatchingPublished.await()
            val ticket = checkNotNull(engine.state.value.attribution).owner
            assertEquals("seed", engine.get(Freshness.LocalOnly))

            sourceOfTruth.releaseWriteReturn.complete(Unit)
            ticket.disposition.first { it is FetchDisposition.Committed }
            assertEquals("fresh", engine.get(Freshness.LocalOnly))
            firstMatching.release.complete(Unit)

            val committed = assertIs<StoreResult.Data<String>>(owner.awaitItem())
            assertEquals("fresh", committed.value)
            assertEquals(Origin.FETCHER, committed.origin)
            val observed = assertIs<StoreResult.Data<String>>(observer.awaitItem())
            assertEquals("fresh", observed.value)
            assertEquals(Origin.FETCHER, observed.origin)
            owner.expectNoEvents()
            observer.expectNoEvents()
            assertEquals(1, calls)
            owner.cancelAndIgnoreRemainingEvents()
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun postCutoffExternalRow_winsWhileTheCommitOutcomeTailIsStillPending() = runTest {
        val key = TestKey("post-cutoff-external-row")
        val sourceOfTruth = SingleWriterCurrentSourceOfTruth()
        val bookkeeper = GateSuccessBookkeeper()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { FetcherResult.Success("fresh") },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()
            bookkeeper.gateNextSuccess()

            val owner = async { engine.get(Freshness.CachedOrFetch) }
            sourceOfTruth.writerCurrentPublished.await()
            sourceOfTruth.releaseWriteReturn.complete(Unit)
            bookkeeper.successEntered.await()

            sourceOfTruth.publishExternal("external")
            var external: StoreResult.Data<String>? = null
            while (external == null) {
                val item = observer.awaitItem()
                if (item is StoreResult.Data && item.value == "external") external = item
            }
            assertEquals(Origin.SOT, checkNotNull(external).origin)
            assertEquals("external", engine.get(Freshness.LocalOnly))

            bookkeeper.releaseSuccess.complete(Unit)
            owner.await()
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearReaderGeneration_dropsAGatedOlderRawRow() = runTest {
        val key = TestKey("clear-drops-old-raw")
        val sourceOfTruth = StartupRaceSourceOfTruth()
        val mappingGate = SequencedGate()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { FetcherResult.Success("unused") },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val oldRow = mappingGate.gateNext()
            sourceOfTruth.publish("old")
            oldRow.entered.await()
            engine.clear()
            oldRow.release.complete(Unit)
            testScheduler.runCurrent()
            assertTrue(runCatching { engine.get(Freshness.LocalOnly) }.isFailure)

            sourceOfTruth.publish("new")
            var delivered: StoreResult.Data<String>? = null
            while (delivered == null) {
                val item = observer.awaitItem()
                if (item is StoreResult.Data) {
                    assertEquals("new", item.value)
                    delivered = item
                }
            }
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedActiveWriterRow_isDroppedAndReaderPipelineRecovers() = runTest {
        val key = TestKey("failed-active-writer-row")
        val sourceOfTruth = FailingWriterSourceOfTruth()
        val mappingGate = SequencedGate()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { FetcherResult.Success("fresh") },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val writerCurrentRow = mappingGate.gateNext()
            val owner = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(owner.awaitItem()).value)
            writerCurrentRow.entered.await()
            val ticket = checkNotNull(engine.state.value.attribution).owner
            writerCurrentRow.release.complete(Unit)
            sourceOfTruth.releaseFailure.complete(Unit)
            ticket.disposition.first { it == FetchDisposition.Failed }

            assertEquals("seed", engine.get(Freshness.LocalOnly))
            observer.expectNoEvents()
            sourceOfTruth.publishExternal("external")
            val external = assertIs<StoreResult.Data<String>>(observer.awaitItem())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            owner.cancelAndIgnoreRemainingEvents()
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedWrite_postMatchExternalRow_resumesAsSotAfterTerminalCleanup() = runTest {
        val key = TestKey("failed-write-post-match-external")
        val sourceOfTruth = FailingAfterPostMatchExternalSourceOfTruth()
        val mappingGate = SequencedGate()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { FetcherResult.Success("fresh") },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val matchingRow = mappingGate.gateNext()
            val postMatchExternalRow = mappingGate.gateNext()
            val owner = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(owner.awaitItem()).value)
            matchingRow.entered.await()
            val ticket = checkNotNull(engine.state.value.attribution).owner

            sourceOfTruth.releaseExternal.complete(Unit)
            sourceOfTruth.externalPublished.await()
            matchingRow.release.complete(Unit)
            postMatchExternalRow.entered.await()
            postMatchExternalRow.release.complete(Unit)
            testScheduler.runCurrent()

            assertEquals("seed", engine.get(Freshness.LocalOnly))
            observer.expectNoEvents()

            sourceOfTruth.releaseFailure.complete(Unit)
            ticket.disposition.first { it == FetchDisposition.Failed }
            val external = assertIs<StoreResult.Data<String>>(observer.awaitItem())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            assertEquals("external", engine.get(Freshness.LocalOnly))
            owner.cancelAndIgnoreRemainingEvents()
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeWriterCurrentRow_restoresProvenanceOnlyAfterDurableReturn() = runTest {
        val key = TestKey("active-writer-current-row")
        val sourceOfTruth = InterleavedWriteSourceOfTruth()
        val mappingGate = SequencedGate()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> {
                            fetchStarted.complete(Unit)
                            releaseFetch.await()
                            FetcherResult.Success("fresh")
                        }

                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val mismatchedRow = mappingGate.gateNext()
            val writerCurrentRow = mappingGate.gateNext()
            val collector =
                engine.stream(Freshness.MaxAge(notOlderThan = 5.minutes))
                    .testIn(backgroundScope)

            assertIs<StoreResult.Loading>(collector.awaitItem())
            fetchStarted.await()
            releaseFetch.complete(Unit)

            mismatchedRow.entered.await()
            mismatchedRow.release.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(null, engine.state.value.attribution)
            collector.expectNoEvents()
            val mismatched = assertIs<StoreResult.Data<String>>(observer.awaitItem())
            assertEquals("mismatched", mismatched.value)
            assertEquals(Origin.SOT, mismatched.origin)

            sourceOfTruth.releaseWriterCurrent.complete(Unit)
            writerCurrentRow.entered.await()
            sourceOfTruth.writerCurrentPublished.await()
            writerCurrentRow.release.complete(Unit)
            testScheduler.runCurrent()
            collector.expectNoEvents()
            assertEquals("mismatched", engine.get(Freshness.LocalOnly))
            observer.expectNoEvents()

            val passive = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            val heldBaseline = assertIs<StoreResult.Data<String>>(passive.awaitItem())
            assertEquals("mismatched", heldBaseline.value)
            passive.expectNoEvents()

            sourceOfTruth.releaseWriteReturn.complete(Unit)
            val committed = assertIs<StoreResult.Data<String>>(collector.awaitItem())
            assertEquals("fresh", committed.value)
            assertEquals(Origin.FETCHER, committed.origin)
            assertFalse(committed.isStale)
            assertFalse(committed.refreshing)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(passive.awaitItem()).value)
            assertEquals("fresh", engine.get(Freshness.LocalOnly))
            assertEquals(1, calls)
            observer.cancelAndIgnoreRemainingEvents()
            passive.cancelAndIgnoreRemainingEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun matchingWriterCurrentRow_staysOutsideResidenceUntilDurableReturn() = runTest {
        val key = TestKey("matching-writer-current-row")
        val sourceOfTruth = SingleWriterCurrentSourceOfTruth()
        val mappingGate = SequencedGate()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("fresh")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val writerCurrentRow = mappingGate.gateNext()
            val collector =
                engine.stream(Freshness.MaxAge(notOlderThan = 5.minutes))
                    .testIn(backgroundScope)
            assertIs<StoreResult.Loading>(collector.awaitItem())
            fetchStarted.await()
            releaseFetch.complete(Unit)

            writerCurrentRow.entered.await()
            sourceOfTruth.writerCurrentPublished.await()
            writerCurrentRow.release.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(null, engine.state.value.attribution)
            assertEquals("seed", engine.get(Freshness.LocalOnly))
            observer.expectNoEvents()
            collector.expectNoEvents()

            val passive = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(passive.awaitItem()).value)
            passive.expectNoEvents()

            sourceOfTruth.releaseWriteReturn.complete(Unit)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            assertEquals("fresh", assertIs<StoreResult.Data<String>>(passive.awaitItem()).value)
            assertEquals("fresh", engine.get(Freshness.LocalOnly))
            observer.cancelAndIgnoreRemainingEvents()
            passive.cancelAndIgnoreRemainingEvents()
            collector.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cancelledActiveWriterRow_isDroppedAndReaderPipelineRecovers() = runTest {
        val key = TestKey("cancelled-active-writer-row")
        val sourceOfTruth = CancellingWriterSourceOfTruth()
        val mappingGate = SequencedGate()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher { FetcherResult.Success("fresh") },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
            )

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        app.cash.turbine.turbineScope {
            val observer = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
            sourceOfTruth.liveReaderStarted.await()
            testScheduler.runCurrent()

            val writerCurrentRow = mappingGate.gateNext()
            val owner = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
            assertEquals("seed", assertIs<StoreResult.Data<String>>(owner.awaitItem()).value)
            writerCurrentRow.entered.await()
            sourceOfTruth.writerCurrentPublished.await()
            val ticket = checkNotNull(engine.state.value.attribution).owner
            writerCurrentRow.release.complete(Unit)
            testScheduler.runCurrent()

            assertEquals("seed", engine.get(Freshness.LocalOnly))
            observer.expectNoEvents()
            sourceOfTruth.releaseCancellation.complete(Unit)
            ticket.disposition.first { it == FetchDisposition.Cancelled }
            testScheduler.runCurrent()
            assertEquals("seed", engine.get(Freshness.LocalOnly))
            observer.expectNoEvents()
            owner.cancelAndIgnoreRemainingEvents()

            sourceOfTruth.publishExternal("external")
            val external = assertIs<StoreResult.Data<String>>(observer.awaitItem())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            observer.cancelAndIgnoreRemainingEvents()
        }
    }

    private class InitialDeliveryGate {
        private var armed = false
        val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        fun arm() {
            armed = true
        }

        suspend fun awaitIfArmed() {
            if (!armed) return
            armed = false
            entered.complete(Unit)
            released.await()
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private class SequencedGate {
        private val queued = ArrayDeque<Step>()

        fun gateNext(): Step = Step().also(queued::addLast)

        suspend fun awaitIfQueued() {
            val step = queued.removeFirstOrNull() ?: return
            step.entered.complete(Unit)
            step.release.await()
        }

        class Step {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }
    }

    private data class ColdPathContractObservation(
        val origin: Origin,
        val isStale: Boolean,
        val refreshing: Boolean,
        val hasMeta: Boolean,
        val fetchCalls: Int,
    )

    private class HeldColdObservationSourceOfTruth(
        private val immediateReaderCalls: Int = 1,
    ) : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        private var readerCalls = 0
        val coldObservationHeld = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls <= immediateReaderCalls) return rows
            return flow {
                var first = true
                var heldColdObservation = false
                rows.collect { value ->
                    if (first && value == null) {
                        first = false
                        heldColdObservation = true
                        coldObservationHeld.complete(Unit)
                        delay(COLD_OBSERVATION_HOLD)
                    } else if (heldColdObservation) {
                        heldColdObservation = false
                        delay(WRITER_CURRENT_DELIVERY_GAP)
                    }
                    emit(value)
                }
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

    private class ConsecutiveWriteEchoSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>(extraBufferCapacity = 2)
        private var readerCalls = 0
        private var current: String? = null
        val liveReaderStarted = CompletableDeferred<Unit>()
        val replacementReaderStarted = CompletableDeferred<Unit>()
        val firstEchoHeld = CompletableDeferred<Unit>()
        val releaseFirstEcho = CompletableDeferred<Unit>()
        val secondWriteStarted = CompletableDeferred<Unit>()
        val releaseSecondWriteReturn = CompletableDeferred<Unit>()
        val releaseSecondEcho = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            val readerCall = ++readerCalls
            val isLiveReader = readerCall >= 2
            return flow {
                emit(current)
                if (!isLiveReader) return@flow
                liveReaderStarted.complete(Unit)
                if (readerCall >= 3) replacementReaderStarted.complete(Unit)
                liveRows.collect { value ->
                    when (value) {
                        "first" -> {
                            firstEchoHeld.complete(Unit)
                            releaseFirstEcho.await()
                        }

                        "second" -> releaseSecondEcho.await()
                    }
                    emit(value)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            current = value
            check(liveRows.tryEmit(value))
            if (value == "second") {
                secondWriteStarted.complete(Unit)
                releaseSecondWriteReturn.await()
            }
        }

        override suspend fun delete(key: TestKey) {
            current = null
            check(liveRows.tryEmit(null))
        }

        fun publishExternal(value: String) {
            current = value
            check(liveRows.tryEmit(value))
        }
    }

    private class FailingSuccessorSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val firstNotificationQueued = CompletableDeferred<Unit>()
        val releaseFirstNotification = CompletableDeferred<Unit>()
        val secondRawCaptured = CompletableDeferred<Unit>()
        val releaseRollback = CompletableDeferred<Unit>()
        val rollbackRawCaptured = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            val readerCall = ++readerCalls
            if (readerCall == 1) return flow { emit(rows.value) }
            return flow {
                emit(rows.value)
                liveReaderStarted.complete(Unit)
                emitAll(
                    rows.drop(1).transformLatest { value ->
                        if (value == "first" && !firstNotificationQueued.isCompleted) {
                            firstNotificationQueued.complete(Unit)
                            releaseFirstNotification.await()
                        } else {
                            emit(value)
                            when (value) {
                                "second" -> secondRawCaptured.complete(Unit)
                                "first" -> rollbackRawCaptured.complete(Unit)
                            }
                        }
                    },
                )
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
            if (value == "first") {
                firstNotificationQueued.await()
                return
            }

            check(value == "second")
            secondRawCaptured.await()
            releaseRollback.await()
            rows.value = "first"
            rollbackRawCaptured.await()
            releaseFailure.await()
            throw IllegalStateException("successor write failed")
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class ReplayEveryRowSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableSharedFlow<String?>(replay = 1)
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()

        init {
            rows.tryEmit("seed")
        }

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return rows
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }

        suspend fun publish(value: String) {
            rows.emit(value)
        }
    }

    private class InterleavedWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>()
        private var readerCalls = 0
        private var current: String? = "seed"
        val liveReaderStarted = CompletableDeferred<Unit>()
        val writerCurrentPublished = CompletableDeferred<Unit>()
        val releaseWriterCurrent = CompletableDeferred<Unit>()
        val releaseWriteReturn = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                if (isLiveReader) liveReaderStarted.complete(Unit)
                emit(current)
                if (isLiveReader) liveRows.collect { emit(it) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            current = "mismatched"
            liveRows.emit("mismatched")
            releaseWriterCurrent.await()
            current = value
            liveRows.emit(value)
            writerCurrentPublished.complete(Unit)
            releaseWriteReturn.await()
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }
    }

    private class SingleWriterCurrentSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>()
        private var readerCalls = 0
        private var current: String? = "seed"
        val liveReaderStarted = CompletableDeferred<Unit>()
        val writerCurrentPublished = CompletableDeferred<Unit>()
        val releaseWriteReturn = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                if (isLiveReader) liveReaderStarted.complete(Unit)
                emit(current)
                if (isLiveReader) liveRows.collect { emit(it) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            current = value
            liveRows.emit(value)
            writerCurrentPublished.complete(Unit)
            releaseWriteReturn.await()
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            current = value
            liveRows.emit(value)
        }
    }

    private class DelayedWriterEchoSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows =
            MutableSharedFlow<String?>(
                replay = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            ).also { check(it.tryEmit("seed")) }
        var readerCalls: Int = 0
            private set
        val liveReaderStarted = CompletableDeferred<Unit>()
        val writerEchoHeld = CompletableDeferred<Unit>()
        val releaseWriterEcho = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                rows.collect { value ->
                    if (isLiveReader && value == "echo" && !writerEchoHeld.isCompleted) {
                        writerEchoHeld.complete(Unit)
                        releaseWriterEcho.await()
                    }
                    emit(value)
                    if (isLiveReader) liveReaderStarted.complete(Unit)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }
    }

    private class ConflatedWriterEchoSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows =
            MutableSharedFlow<String?>(
                replay = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            ).also { check(it.tryEmit("seed")) }
        var readerCalls: Int = 0
            private set
        val liveReaderHeld = CompletableDeferred<Unit>()
        val releaseLiveReader = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                rows.collect { value ->
                    emit(value)
                    if (isLiveReader && !liveReaderHeld.isCompleted) {
                        liveReaderHeld.complete(Unit)
                        releaseLiveReader.await()
                    }
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            rows.emit(value)
        }
    }

    private object PassThroughOverlay : Overlay<TestKey, String> {
        override fun apply(
            key: TestKey,
            base: String?,
        ): String? = base

        override val changes: Flow<StoreKey> = emptyFlow()
    }

    private class CancellingWriterSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>()
        private var readerCalls = 0
        private var current: String? = "seed"
        val liveReaderStarted = CompletableDeferred<Unit>()
        val writerCurrentPublished = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                if (isLiveReader) liveReaderStarted.complete(Unit)
                emit(current)
                if (isLiveReader) liveRows.collect { emit(it) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            val previous = current
            current = value
            liveRows.emit(value)
            writerCurrentPublished.complete(Unit)
            releaseCancellation.await()
            current = previous
            liveRows.emit(previous)
            throw kotlinx.coroutines.CancellationException("cancelled write")
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            current = value
            liveRows.emit(value)
        }
    }

    private class MatchingOtherMatchingSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>()
        private var readerCalls = 0
        private var current: String? = "seed"
        val liveReaderStarted = CompletableDeferred<Unit>()
        val finalMatchingPublished = CompletableDeferred<Unit>()
        val releaseWriteReturn = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                if (isLiveReader) liveReaderStarted.complete(Unit)
                emit(current)
                if (isLiveReader) liveRows.collect { emit(it) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            current = value
            liveRows.emit(value)
            current = "intermediate"
            liveRows.emit("intermediate")
            current = value
            liveRows.emit(value)
            finalMatchingPublished.complete(Unit)
            releaseWriteReturn.await()
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }
    }

    private class FailingWriterSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>()
        private var readerCalls = 0
        private var current: String? = "seed"
        val liveReaderStarted = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                if (isLiveReader) liveReaderStarted.complete(Unit)
                emit(current)
                if (isLiveReader) liveRows.collect { emit(it) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            val previous = current
            current = value
            liveRows.emit(value)
            releaseFailure.await()
            current = previous
            liveRows.emit(previous)
            throw IllegalStateException("write failed")
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            current = value
            liveRows.emit(value)
        }
    }

    private class FailingAfterPostMatchExternalSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>()
        private var readerCalls = 0
        private var current: String? = "seed"
        val liveReaderStarted = CompletableDeferred<Unit>()
        val releaseExternal = CompletableDeferred<Unit>()
        val externalPublished = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                if (isLiveReader) liveReaderStarted.complete(Unit)
                emit(current)
                if (isLiveReader) liveRows.collect { emit(it) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            current = value
            liveRows.emit(value)
            releaseExternal.await()
            current = "external"
            liveRows.emit(current)
            externalPublished.complete(Unit)
            releaseFailure.await()
            throw IllegalStateException("write failed")
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }
    }

    private class StartupRaceSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val call = readerCalls
            return flow {
                if (call >= 2) liveReaderStarted.complete(Unit)
                rows.collect { emit(it) }
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

        fun publish(value: String) {
            rows.value = value
        }

        fun publishAbsent() {
            rows.value = null
        }
    }

    private class WriteStartedSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        val writeStarted = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeStarted.complete(Unit)
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class GatedWriterReturnSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeStarted.complete(Unit)
            releaseWrite.await()
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class FailureSignallingBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        val failureRecorded = CompletableDeferred<Unit>()

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            delegate.recordFailure(key, atEpochMillis)
            failureRecorded.complete(Unit)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            delegate.forget(key)
        }

        override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }

    private class GateSuccessBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        private var gateNext = false
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val failureRecorded = CompletableDeferred<Unit>()

        fun gateNextSuccess() {
            gateNext = true
        }

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            delegate.recordSuccess(key, meta)
            if (gateNext) {
                gateNext = false
                successEntered.complete(Unit)
                releaseSuccess.await()
            }
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            delegate.recordFailure(key, atEpochMillis)
            failureRecorded.complete(Unit)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            delegate.forget(key)
        }

        override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }

    /** Pauses the selected success before it can clear durable staleness in the delegate. */
    private class PreDelegateSuccessGateBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        private var gateNext = false
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()

        fun gateNextSuccess() {
            gateNext = true
        }

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            if (gateNext) {
                gateNext = false
                successEntered.complete(Unit)
                releaseSuccess.await()
            }
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) = delegate.recordFailure(key, atEpochMillis)

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) = delegate.forget(key)

        override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }

    /** Pauses one selected status call after it has captured the delegate's immutable snapshot. */
    private class PostDelegateStatusGateBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        private val statusCallsUntilGate = MutableStateFlow(0)
        val statusEntered = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()

        fun gateStatusCall(number: Int) {
            require(number > 0) { "number must be positive" }
            check(statusCallsUntilGate.compareAndSet(expect = 0, update = number)) {
                "a status gate is already armed"
            }
        }

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) = delegate.recordSuccess(key, meta)

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) = delegate.recordFailure(key, atEpochMillis)

        override suspend fun status(key: StoreKey): KeyStatus? {
            val captured = delegate.status(key)
            while (true) {
                val remaining = statusCallsUntilGate.value
                if (remaining == 0) return captured
                if (statusCallsUntilGate.compareAndSet(remaining, remaining - 1)) {
                    if (remaining == 1) {
                        statusEntered.complete(Unit)
                        releaseStatus.await()
                    }
                    return captured
                }
            }
        }

        override suspend fun forget(key: StoreKey) = delegate.forget(key)

        override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }

    private class GateFailureBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        private var gateNext = false
        val failureEntered = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()

        fun gateNextFailure() {
            gateNext = true
        }

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            delegate.recordFailure(key, atEpochMillis)
            if (gateNext) {
                gateNext = false
                failureEntered.complete(Unit)
                releaseFailure.await()
            }
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            delegate.forget(key)
        }

        override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }

    private class GateForgetBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        private var gateNext = false
        val forgetEntered = CompletableDeferred<Unit>()
        val releaseForget = CompletableDeferred<Unit>()

        fun gateNextForget() {
            gateNext = true
        }

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            delegate.recordFailure(key, atEpochMillis)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            delegate.forget(key)
            if (gateNext) {
                gateNext = false
                forgetEntered.complete(Unit)
                releaseForget.await()
            }
        }

        override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }
}
