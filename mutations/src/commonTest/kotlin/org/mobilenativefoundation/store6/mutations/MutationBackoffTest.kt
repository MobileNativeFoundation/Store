@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationBackoffTest {
    @Test
    fun eligibilityDerivesFromDurableAttemptFactsUnderExponentialCapAndFullJitter() = runTest {
        val observedWindows = mutableListOf<Long>()

        for (attempt in listOf(1, 2, 3, 16)) {
            val storage = InMemoryMutationJournalStorage()
            val mutations = BackoffMutations()
            val server =
                BackoffServer(
                    failuresRemaining = attempt,
                    retainRetiredRows = true,
                )
            val clock = SchedulerBackoffClock { testScheduler.currentTime }
            val computed = backoffWindow(attempt)
            val seed = seedWithPositiveFirstJitter(computed)
            val expectedJitter = Random(seed).nextLong(computed + 1L)
            val key = MutationsTestKey("eligibility-$attempt")
            val writer = openBackoffEngine(storage, mutations, server, clock, Random(seed))

            writer.mutate(key, mutations.append, "+mine")
            repeat(attempt) { writer.drain(key) }
            val failed = backoffState(storage)
            assertEquals(attempt, failed.attempt)
            assertEquals(testScheduler.currentTime, failed.lastAttemptAt)
            observedWindows += computed

            server.failuresRemaining = 0
            writer.clearLiveKeyCache()
            testScheduler.advanceTimeBy(expectedJitter - 1L)
            val early = openBackoffEngine(storage, mutations, server, clock, Random(seed))
            early.drain()
            assertEquals(attempt, server.pushes.size, "attempt $attempt pushed before eligibility")
            assertEquals(StoredPhase.READY, backoffState(storage).phase)

            testScheduler.advanceTimeBy(1L)
            val atBoundary = openBackoffEngine(storage, mutations, server, clock, Random(seed))
            atBoundary.drain()
            assertEquals(attempt + 1, server.pushes.size)
            assertEquals(StoredPhase.RETIRED, backoffState(storage).phase)
            assertTrue(expectedJitter in 0L..computed)
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 300_000L), observedWindows)

        val attemptZeroStorage = InMemoryMutationJournalStorage()
        val attemptZeroMutations = BackoffMutations()
        val attemptZeroServer = BackoffServer(retainRetiredRows = true)
        val attemptZeroRandom = CountingBackoffRandom(Random(47))
        val attemptZeroKey = MutationsTestKey("attempt-zero-no-rng")
        val attemptZero =
            openBackoffEngine(
                attemptZeroStorage,
                attemptZeroMutations,
                attemptZeroServer,
                SchedulerBackoffClock { testScheduler.currentTime },
                attemptZeroRandom,
            )
        attemptZero.mutate(attemptZeroKey, attemptZeroMutations.append, "+mine")
        attemptZero.clearLiveKeyCache()
        attemptZero.drain()
        assertEquals(0, attemptZeroRandom.nextBitsCalls, "attempt zero must not consume RNG")
        assertEquals(StoredPhase.RETIRED, backoffState(attemptZeroStorage).phase)

        val refreshStorage = InMemoryMutationJournalStorage()
        val refreshMutations = BackoffMutations()
        val refreshServer = BackoffServer(failuresRemaining = 1)
        val refreshClock = SchedulerBackoffClock { testScheduler.currentTime }
        val refreshKey = MutationsTestKey("refresh-required-eligibility")
        val refreshWriter =
            openBackoffEngine(
                refreshStorage,
                refreshMutations,
                refreshServer,
                refreshClock,
                Random(48),
            )
        refreshWriter.mutate(refreshKey, refreshMutations.append, "+mine")
        refreshWriter.drain(refreshKey)
        seedRefreshRequired(refreshStorage, testScheduler.currentTime)
        val refreshBefore = backoffState(refreshStorage)
        var resolverCalls = 0
        val refreshRandom =
            CountingBackoffRandom(Random(seedWithPositiveFirstJitter(backoffWindow(2))))
        val refreshReader =
            openBackoffEngine(
                refreshStorage,
                refreshMutations,
                refreshServer,
                refreshClock,
                refreshRandom,
                resolver = MutationKeyResolver {
                    resolverCalls += 1
                    throw IllegalStateException("REFRESH_REQUIRED resolved before eligibility")
                },
            )
        refreshReader.drain()
        assertTrue(refreshRandom.nextBitsCalls > 0, "REFRESH_REQUIRED must derive jitter")
        assertEquals(0, resolverCalls, "REFRESH_REQUIRED must preflight before resolution")
        assertEquals(refreshBefore, backoffState(refreshStorage))
    }

    @Test
    fun jitterIsRedrawnPerPassAndNeverPersisted() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = BackoffMutations()
        val server =
            BackoffServer(
                failuresRemaining = 1,
                retainRetiredRows = true,
            )
        val clock = SchedulerBackoffClock { testScheduler.currentTime }

        val captureStorage = InMemoryMutationJournalStorage()
        val captureKey = MutationsTestKey("capture-cancellation-attempt-facts")
        val captureEngine =
            openBackoffEngine(
                captureStorage,
                mutations,
                BackoffServer(),
                clock,
                Random(40),
                baseReader = { throw CancellationException("capture cancelled") },
            )
        captureEngine.mutate(captureKey, mutations.append, "+mine")
        assertFailsWith<CancellationException> { captureEngine.drain(captureKey) }
        val captureCancelled = backoffState(captureStorage)
        assertEquals(StoredPhase.UNPREPARED, captureCancelled.phase)
        assertEquals(0, captureCancelled.attempt)
        assertNull(captureCancelled.lastAttemptAt)
        assertNull(captureCancelled.activeFailureKind)
        assertEquals(0, captureCancelled.failureCount)
        val redraw = seedWithDescendingFirstTwoJitters(1_000L)
        val key = MutationsTestKey("redrawn-per-pass")
        val engine = openBackoffEngine(storage, mutations, server, clock, Random(redraw.seed))

        engine.mutate(key, mutations.append, "+mine")
        engine.drain(key)
        server.failuresRemaining = 0
        engine.clearLiveKeyCache()
        testScheduler.advanceTimeBy(redraw.second)

        engine.drain()
        assertEquals(1, server.pushes.size)
        assertEquals(StoredPhase.READY, backoffState(storage).phase)

        val reopened = openBackoffEngine(storage, mutations, server, clock, Random(redraw.seed))
        reopened.drain()
        assertEquals(1, server.pushes.size, "restart must not reuse a persisted jitter")
        assertEquals(StoredPhase.READY, backoffState(storage).phase)

        engine.drain()
        assertEquals(2, server.pushes.size)
        assertEquals(StoredPhase.RETIRED, backoffState(storage).phase)
    }

    @Test
    fun restartRecomputesEligibilityFromDurableFactsAlone() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = BackoffMutations()
        val server =
            BackoffServer(
                failuresRemaining = 1,
                retainRetiredRows = true,
            )
        val clock = SchedulerBackoffClock { testScheduler.currentTime }
        val seeds = seedsStraddling(1_000L, 500L)
        val key = MutationsTestKey("restart-facts-only")
        val writer = openBackoffEngine(storage, mutations, server, clock, Random(seeds.highSeed))

        writer.mutate(key, mutations.append, "+mine")
        writer.drain(key)
        server.failuresRemaining = 0
        writer.clearLiveKeyCache()
        testScheduler.advanceTimeBy(500L)

        val beforeRestart =
            openBackoffEngine(storage, mutations, server, clock, Random(seeds.highSeed))
        beforeRestart.drain()
        assertEquals(1, server.pushes.size)
        assertEquals(StoredPhase.READY, backoffState(storage).phase)

        val afterRestart =
            openBackoffEngine(storage, mutations, server, clock, Random(seeds.lowSeed))
        afterRestart.drain()
        assertEquals(2, server.pushes.size)
        assertEquals(StoredPhase.RETIRED, backoffState(storage).phase)
    }

    @Test
    fun attemptFactsAdvanceOnlyOnCompletedTransportAttempts() = runTest {
        val mutations = BackoffMutations()
        val clock = SchedulerBackoffClock { testScheduler.currentTime }

        val resolverStorage = InMemoryMutationJournalStorage()
        val resolverEngine =
            openBackoffEngine(
                resolverStorage,
                mutations,
                BackoffServer(),
                clock,
                Random(41),
                resolver = MutationKeyResolver { throw IllegalStateException("resolver failed") },
            )
        val resolverKey = MutationsTestKey("resolver-attempt-facts")
        resolverEngine.mutate(resolverKey, mutations.append, "+mine")
        resolverEngine.clearLiveKeyCache()
        resolverEngine.drain()
        val resolverPark = backoffState(resolverStorage)
        assertEquals(StoredPhase.PARKED, resolverPark.phase)
        assertEquals(0, resolverPark.attempt)
        assertNull(resolverPark.lastAttemptAt)
        assertEquals(MutationFailureKind.IDENTITY, resolverPark.activeFailureKind)

        val codecStorage = InMemoryMutationJournalStorage()
        val cancellingServer = BackoffServer()
        cancellingServer.pushBehavior = { throw CancellationException("capture completed") }
        val codecKey = MutationsTestKey("codec-attempt-facts")
        val capturing =
            openBackoffEngine(
                codecStorage,
                mutations,
                cancellingServer,
                clock,
                Random(42),
                valueCodecVersion = 99,
                valueCodec = VersionBoundStringCodec(99),
            )
        capturing.mutate(codecKey, mutations.append, "+mine")
        assertFailsWith<CancellationException> { capturing.drain(codecKey) }
        val captured = backoffState(codecStorage)
        assertEquals(StoredPhase.INFLIGHT, captured.phase)
        assertEquals(0, captured.attempt)
        assertNull(captured.lastAttemptAt)
        assertNull(captured.activeFailureKind)

        val codecPark =
            openBackoffEngine(
                codecStorage,
                mutations,
                BackoffServer(),
                clock,
                Random(43),
                valueCodec = VersionBoundStringCodec(1),
            )
        codecPark.drain()
        val parked = backoffState(codecStorage)
        assertEquals(StoredPhase.PARKED, parked.phase)
        assertEquals(0, parked.attempt)
        assertNull(parked.lastAttemptAt)
        assertEquals(MutationFailureKind.CODEC, parked.activeFailureKind)

        val transportStorage = InMemoryMutationJournalStorage()
        val transportServer = BackoffServer(failuresRemaining = 1)
        val transport =
            openBackoffEngine(transportStorage, mutations, transportServer, clock, Random(44))
        val transportKey = MutationsTestKey("transport-attempt-facts")
        transport.mutate(transportKey, mutations.append, "+mine")
        transport.drain(transportKey)
        val failedTransport = backoffState(transportStorage)
        assertEquals(StoredPhase.READY, failedTransport.phase)
        assertEquals(1, failedTransport.attempt)
        assertEquals(testScheduler.currentTime, failedTransport.lastAttemptAt)
        assertEquals(1, failedTransport.failureCount)
    }

    @Test
    fun notYetEligibleEntry_isSkippedByGlobalDrainWithoutFailureOrPark() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = BackoffMutations()
        val server =
            BackoffServer(
                failuresRemaining = 1,
                retainRetiredRows = true,
            )
        val clock = SchedulerBackoffClock { testScheduler.currentTime }
        val seed = seedWithPositiveFirstJitter(1_000L)
        val key = MutationsTestKey("global-skip")
        val engine = openBackoffEngine(storage, mutations, server, clock, Random(seed))

        engine.mutate(key, mutations.append, "+mine")
        engine.drain(key)
        val beforeSkip = backoffState(storage)
        server.failuresRemaining = 0
        engine.clearLiveKeyCache()

        engine.drain()
        val afterSkip = backoffState(storage)
        assertEquals(1, server.pushes.size)
        assertEquals(beforeSkip, afterSkip)
        assertEquals(StoredPhase.READY, afterSkip.phase)

        testScheduler.advanceTimeBy(1_001L)
        engine.drain()
        assertEquals(2, server.pushes.size)
        assertEquals(StoredPhase.RETIRED, backoffState(storage).phase)
    }

    @Test
    fun keyedDrainOverridesBackoffEligibility() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = BackoffMutations()
        val server =
            BackoffServer(
                failuresRemaining = 1,
                retainRetiredRows = true,
            )
        val clock = SchedulerBackoffClock { testScheduler.currentTime }
        val key = MutationsTestKey("keyed-override")
        val engine = openBackoffEngine(storage, mutations, server, clock, Random(45))

        engine.mutate(key, mutations.append, "+mine")
        engine.drain(key)
        assertEquals(StoredPhase.READY, backoffState(storage).phase)
        server.failuresRemaining = 0

        engine.drain(key)
        assertEquals(2, server.pushes.size)
        assertEquals(StoredPhase.RETIRED, backoffState(storage).phase)
    }
}

private const val BACKOFF_CLIENT_ID: String = "backoff-client"

private class BackoffMutations {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            append =
                mutator(
                    id = "backoff-append",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                    )
                }
        }
}

private class BackoffServer(
    var failuresRemaining: Int = 0,
    private val retainRetiredRows: Boolean = false,
) : MutationServer<MutationsTestKey, String> {
    val pushes = mutableListOf<MutationPush<MutationsTestKey, String>>()
    var pushBehavior: suspend (MutationPush<MutationsTestKey, String>) -> Unit = {}

    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> {
        pushes += request
        pushBehavior(request)
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            throw IllegalStateException("scripted completed transport failure")
        }
        return when (val mine = request.mine) {
            is MutationPresence.Present ->
                MutationPresentAck(mine.value, "etag-${pushes.size}", null)
            MutationPresence.Absent -> MutationAbsentAck("etag-${pushes.size}")
        }
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(
            confirmedThroughSequence =
                if (retainRetiredRows) {
                    0L
                } else {
                    request.retiredThroughSequence
                },
        )
}

private class VersionBoundStringCodec(
    private val supportedVersion: Int,
) : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(version: Int, bytes: ByteArray): String {
        require(version == supportedVersion) {
            "Only codec version $supportedVersion is available; was $version."
        }
        return bytes.decodeToString()
    }
}

private class SchedulerBackoffClock(
    private val now: () -> Long,
) : WallClock {
    override fun nowEpochMillis(): Long = now()
}

private object BackoffNoopHandle : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(key: MutationsTestKey, value: String) = Unit

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(key: MutationsTestKey, etag: String?) = Unit
}

private fun openBackoffEngine(
    storage: InMemoryMutationJournalStorage,
    mutations: BackoffMutations,
    server: BackoffServer,
    wallClock: WallClock,
    random: Random,
    resolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
    valueCodecVersion: Int = 1,
    valueCodec: MutationCodec<String> = VersionBoundStringCodec(1),
    baseReader: suspend (MutationsTestKey) -> String? = { "base" },
): MutationEngine<MutationsTestKey, String> =
    MutationEngine(
        registry = mutations.registry,
        server = server,
        journal =
            StorageBackedMutationJournal(
                storage = storage,
                registrations = mutations.registry.registrations,
                clientId = BACKOFF_CLIENT_ID,
                hydrateOnFirstUse = true,
            ),
        keyResolver = resolver,
        valueCodecVersion = valueCodecVersion,
        valueCodec = valueCodec,
        baseReader = baseReader,
        wallClock = wallClock,
        backoffRandom = random,
        clientId = BACKOFF_CLIENT_ID,
    ).also { engine -> engine.bind(BackoffNoopHandle) }

private data class BackoffState(
    val phase: StoredPhase,
    val attempt: Int,
    val lastAttemptAt: Long?,
    val activeFailureKind: MutationFailureKind?,
    val failureCount: Int,
)

private suspend fun backoffState(storage: InMemoryMutationJournalStorage): BackoffState =
    storage.transaction { transaction ->
        val execution = transaction.executions(BACKOFF_CLIENT_ID).single()
        val failures = transaction.failures(BACKOFF_CLIENT_ID)
        BackoffState(
            phase = execution.phase,
            attempt = execution.attempt,
            lastAttemptAt = execution.lastAttemptAt,
            activeFailureKind =
                execution.activeFailureId?.let { failureId ->
                    assertNotNull(failures.singleOrNull { it.failureId == failureId }).kind
                },
            failureCount = failures.size,
        )
    }

private fun backoffWindow(attempt: Int): Long {
    require(attempt >= 1)
    var delay = 1_000L
    repeat(attempt - 1) {
        delay = minOf(300_000L, delay * 2L)
    }
    return delay
}

private class CountingBackoffRandom(
    private val delegate: Random,
) : Random() {
    var nextBitsCalls: Int = 0
        private set

    override fun nextBits(bitCount: Int): Int {
        nextBitsCalls += 1
        return delegate.nextBits(bitCount)
    }
}

private suspend fun seedRefreshRequired(
    storage: InMemoryMutationJournalStorage,
    occurredAt: Long,
) {
    storage.transaction { transaction ->
        val execution = transaction.executions(BACKOFF_CLIENT_ID).single()
        val attempt = transaction.attempts(BACKOFF_CLIENT_ID).single()
        transaction.advanceExecution(
            MutationExecutionRecord(
                clientId = execution.clientId,
                clientSequence = execution.clientSequence,
                phase = StoredPhase.INFLIGHT,
                currentGeneration = execution.currentGeneration,
                attempt = execution.attempt,
                lastAttemptAt = execution.lastAttemptAt,
                activeFailureId = null,
                retiredAt = null,
            ),
        )
        transaction.recordConflictReceipt(
            MutationAttemptRecord(
                clientId = attempt.clientId,
                clientSequence = attempt.clientSequence,
                generation = attempt.generation,
                effectiveNamespace = attempt.effectiveNamespace,
                effectiveCanonicalId = attempt.effectiveCanonicalId,
                valueCodecVersion = attempt.valueCodecVersion,
                basePresence = attempt.basePresence,
                baseBlob = attempt.baseBlob,
                minePresence = attempt.minePresence,
                mineBlob = attempt.mineBlob,
                preconditionMetaPresent = attempt.preconditionMetaPresent,
                preconditionWrittenAt = attempt.preconditionWrittenAt,
                preconditionEtag = attempt.preconditionEtag,
                advertisedRetiredThroughSequence = attempt.advertisedRetiredThroughSequence,
                generationIdempotencyKey = attempt.generationIdempotencyKey,
                preparedAt = attempt.preparedAt,
                conflictMetaPresent = false,
                conflictWrittenAt = null,
                conflictEtag = null,
                conflictReceivedAt = occurredAt,
            ),
        )
        transaction.advanceExecution(
            MutationExecutionRecord(
                clientId = execution.clientId,
                clientSequence = execution.clientSequence,
                phase = StoredPhase.REFRESH_REQUIRED,
                currentGeneration = execution.currentGeneration,
                attempt = execution.attempt + 1,
                lastAttemptAt = occurredAt,
                activeFailureId = null,
                retiredAt = null,
            ),
        )
    }
}

private data class RedrawSeed(
    val seed: Int,
    val first: Long,
    val second: Long,
)

private fun seedWithDescendingFirstTwoJitters(window: Long): RedrawSeed {
    for (seed in 1..10_000) {
        val random = Random(seed)
        val first = random.nextLong(window + 1L)
        val second = random.nextLong(window + 1L)
        if (first > second) return RedrawSeed(seed, first, second)
    }
    error("No deterministic redraw seed found")
}

private fun seedWithPositiveFirstJitter(window: Long): Int {
    for (seed in 1..10_000) {
        if (Random(seed).nextLong(window + 1L) > 0L) return seed
    }
    error("No deterministic positive-jitter seed found")
}

private data class StraddlingSeeds(
    val highSeed: Int,
    val lowSeed: Int,
)

private fun seedsStraddling(
    window: Long,
    threshold: Long,
): StraddlingSeeds {
    var high: Int? = null
    var low: Int? = null
    for (seed in 1..10_000) {
        val jitter = Random(seed).nextLong(window + 1L)
        if (jitter > threshold && high == null) high = seed
        if (jitter <= threshold && low == null) low = seed
        if (high != null && low != null) return StraddlingSeeds(high, low)
    }
    error("No deterministic straddling seeds found")
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
