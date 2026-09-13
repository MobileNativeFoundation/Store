@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCheckpointFailed
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyIdentity
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPendingState
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class DrainTestKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("drain")

    override fun canonicalId(): String = id
}

internal object DrainTestKeyResolver : MutationKeyResolver<DrainTestKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): DrainTestKey? =
        if (identity.namespace == "drain") DrainTestKey(identity.canonicalId) else null
}

internal object DrainFixtureStringArgsCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

internal class DrainFixtureBackend : MutationServer<DrainTestKey, String> {
    private val confirmed = mutableMapOf<String, String>()
    private var concurrentPushes = 0
    private var requestedRetirementSequence = 0L

    internal var offline: Boolean = false
    internal val receivedPushes: MutableList<String> = mutableListOf()
    internal var pushGate: CompletableDeferred<Unit>? = null
    internal val pushEntered: CompletableDeferred<Unit> = CompletableDeferred()
    internal var maxConcurrentPushes: Int = 0
        private set
    internal var retireBehavior: suspend () -> MutationRetirementAck = {
        MutationRetirementAck(confirmedThroughSequence = requestedRetirementSequence)
    }

    internal suspend fun load(key: DrainTestKey): String =
        confirmed[key.canonicalId()] ?: "base"

    override suspend fun push(
        request: MutationPush<DrainTestKey, String>,
    ): MutationAck<DrainTestKey, String> {
        concurrentPushes += 1
        maxConcurrentPushes = maxOf(maxConcurrentPushes, concurrentPushes)
        pushEntered.complete(Unit)
        try {
            pushGate?.await()
            check(!offline) { "backend is offline" }
            val value =
                when (val mine = request.mine) {
                    is MutationPresence.Present -> mine.value
                    MutationPresence.Absent ->
                        error("DrainFixtureBackend only supports present pushes.")
                }
            receivedPushes += value
            confirmed[request.key.canonicalId()] = value
            return MutationPresentAck(
                authoritative = value,
                etag = null,
                canonicalKey = null,
            )
        } finally {
            concurrentPushes -= 1
        }
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck {
        requestedRetirementSequence = request.retiredThroughSequence
        return retireBehavior()
    }
}

internal fun throwingOnceSourceOfTruth(): SourceOfTruth<DrainTestKey, String> {
    val delegate = DrainFixtureSourceOfTruth()
    return object : SourceOfTruth<DrainTestKey, String> {
        private var throwNextWrite = true

        override fun reader(key: DrainTestKey): Flow<String?> = delegate.reader(key)

        override suspend fun write(
            key: DrainTestKey,
            value: String,
        ) {
            if (throwNextWrite) {
                throwNextWrite = false
                error("fixture source of truth write failed")
            }
            delegate.write(key, value)
        }

        override suspend fun delete(key: DrainTestKey) = delegate.delete(key)

        override suspend fun deleteNamespace(namespace: StoreNamespace) =
            delegate.deleteNamespace(namespace)

        override suspend fun deleteAll() = delegate.deleteAll()
    }
}

internal class DrainFixture {
    internal val storage: InMemoryMutationJournalStorage = InMemoryMutationJournalStorage()
    internal val backend: DrainFixtureBackend = DrainFixtureBackend()
    internal var nowMillis: Long = 0L
    internal val clock: WallClock =
        object : WallClock {
            override fun nowEpochMillis(): Long = nowMillis
        }
    internal lateinit var appendRef: MutatorRef<DrainTestKey, String, String>
        private set

    private val registry =
        mutatorRegistry<DrainTestKey, String> {
            appendRef =
                mutator(
                    id = "drain-append",
                    version = 1,
                    codec = DrainFixtureStringArgsCodec,
                    stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
                ) { base, suffix ->
                    MutationPresence.Present(
                        ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                    )
                }
        }

    internal fun openStore(
        sourceOfTruth: SourceOfTruth<DrainTestKey, String>? = null,
    ): MutationStore<DrainTestKey, String> =
        mutationStore(
            registry = registry,
            server = backend,
            keyResolver = DrainTestKeyResolver,
            valueCodecVersion = 1,
            valueCodec = DrainFixtureStringArgsCodec,
        ) {
            fetcher { backend.load(it) }
            journalStorage(storage)
            wallClock(clock)
            sourceOfTruth?.let(::persistence)
        }
}

private class DrainFixtureSourceOfTruth : SourceOfTruth<DrainTestKey, String> {
    private class Row(
        val value: String?,
        val version: Long,
    )

    private val lock = Mutex()
    private val rows = HashMap<Pair<String, String>, MutableStateFlow<Row>>()

    override fun reader(key: DrainTestKey): Flow<String?> =
        flow {
            emitAll(rowFor(key).map { row -> row.value })
        }

    override suspend fun write(
        key: DrainTestKey,
        value: String,
    ) {
        publish(key.identity(), value)
    }

    override suspend fun delete(key: DrainTestKey) {
        publish(key.identity(), null)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        lock.withLock {
            rows.forEach { (identity, row) ->
                if (identity.first == namespace.value) {
                    row.publishNull()
                }
            }
        }
    }

    override suspend fun deleteAll() {
        lock.withLock {
            rows.values.forEach { row -> row.publishNull() }
        }
    }

    private suspend fun rowFor(key: DrainTestKey): MutableStateFlow<Row> {
        val identity = key.identity()
        return lock.withLock { rowFor(identity) }
    }

    private suspend fun publish(
        identity: Pair<String, String>,
        value: String?,
    ) {
        lock.withLock {
            val row = rowFor(identity)
            val current = row.value
            row.value = Row(value = value, version = current.version + 1L)
        }
    }

    private fun rowFor(identity: Pair<String, String>): MutableStateFlow<Row> =
        rows.getOrPut(identity) {
            MutableStateFlow(Row(value = null, version = 0L))
        }

    private fun MutableStateFlow<Row>.publishNull() {
        val current = value
        value = Row(value = null, version = current.version + 1L)
    }

    private fun DrainTestKey.identity(): Pair<String, String> =
        namespace.value to canonicalId()
}

class DrainTestFixturesTest {
    @Test
    fun successfulDrainRetiresPendingWriteAndRecordsPush() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("successful"), fixture.appendRef, "+one")

            store.drain()

            assertEquals(emptyList(), store.pendingWrites())
            assertEquals(listOf("+one"), fixture.backend.receivedPushes)
        } finally {
            store.close()
        }
    }

    @Test
    fun offlineDrainLeavesPendingWriteWithIncrementedAttempt() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("offline"), fixture.appendRef, "+pending")

            store.drain()

            val pending = store.pendingWrites().single()
            assertEquals(1, pending.attempt)
            assertEquals(MutationPendingState.PENDING, pending.state)
        } finally {
            store.close()
        }
    }

    @Test
    fun gatedDrainTracksOneConcurrentPush() = runTest {
        val fixture = DrainFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.backend.pushGate = gate
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("gated"), fixture.appendRef, "+gated")

            val pass = async { store.drain() }
            fixture.backend.pushEntered.awaitFromDefaultContext()

            assertEquals(1, fixture.backend.maxConcurrentPushes)
            gate.complete(Unit)
            pass.await()
        } finally {
            store.close()
        }
    }

    @Test
    fun retirementFailureEmitsCheckpointFailureAndReturnsNormally() = runTest {
        val fixture = DrainFixture()
        fixture.backend.retireBehavior = { error("retirement unavailable") }
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("checkpoint"), fixture.appendRef, "+checkpoint")

            store.events.test {
                store.drain()
                var event = awaitItem()
                while (event !is MutationCheckpointFailed) {
                    event = awaitItem()
                }
                assertIs<MutationCheckpointFailed>(event)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun postAckWriteFailureLeavesHeadAdopting() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore(throwingOnceSourceOfTruth())
        try {
            store.mutate(DrainTestKey("adopting"), fixture.appendRef, "+adopting")

            store.drain()

            assertEquals(
                MutationPendingState.ADOPTING,
                store.pendingWrites().single().state,
            )
        } finally {
            store.close()
        }
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)

// RealStore's engine scope is Dispatchers.Default, so it never observes runTest virtual
// time. Waiting on Default is the same barrier used by the core and graphql suites.
internal suspend fun <T> CompletableDeferred<T>.awaitFromDefaultContext(): T =
    withContext(Dispatchers.Default) {
        await()
    }

internal suspend fun awaitUntil(
    timeout: Duration = 10.seconds,
    condition: suspend () -> Boolean,
) {
    withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            check(started.elapsedNow() < timeout) {
                "Condition was not satisfied within $timeout."
            }
            delay(20)
        }
    }
}

// Pump the test scheduler only. Hopping to Default with a real delay lets
// InProcessDrainScheduler's virtual timers run away (advanceUntilIdle-equivalent).
internal suspend fun TestScope.waitUntilCurrent(
    timeout: Duration = 5.seconds,
    condition: suspend () -> Boolean,
) {
    val started = TimeSource.Monotonic.markNow()
    while (!condition()) {
        testScheduler.runCurrent()
        yield()
        check(started.elapsedNow() < timeout) {
            "Condition was not satisfied within $timeout."
        }
    }
}
