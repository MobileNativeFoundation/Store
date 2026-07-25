@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.room

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class RoomSourceOfTruthReaderSemanticsTest {
    private val keyA = RoomKitKey(StoreNamespace("users"), "a")
    private val keyB = RoomKitKey(StoreNamespace("users"), "b")

    @Test
    fun sameTableOtherRowWrite_doesNotReemit(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val equalityObserved = CompletableDeferred<Unit>()
            val sourceOfTruth = equalityProbeSourceOfTruth(database, equalityObserved)
            dao.upsert(row(keyA, "value-a"))

            sourceOfTruth.reader(keyA).test {
                assertEquals("value-a", awaitItem()?.payload)

                dao.upsert(row(keyB, "value-b"))
                equalityObserved.await()
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun externalDaoWrite_isObservedLive(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val sourceOfTruth = sourceOfTruth(database)
            dao.upsert(row(keyA, "value-1"))

            sourceOfTruth.reader(keyA).test {
                assertEquals("value-1", awaitItem())

                dao.upsert(row(keyA, "value-2"))
                assertEquals("value-2", awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun equalValueWriteAfterExternalEqualChange_stillEmits(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val sourceOfTruth = sourceOfTruth(database)

            sourceOfTruth.reader(keyA).test {
                assertNull(awaitItem())

                dao.upsert(row(keyA, "value"))
                assertEquals("value", awaitItem())

                sourceOfTruth.write(keyA, "value")
                assertEquals("value", awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun registrations_removedWhenLastReaderCancels(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)

            turbineScope {
                val first = sourceOfTruth.reader(keyA).testIn(backgroundScope)
                val second = sourceOfTruth.reader(keyA).testIn(backgroundScope)
                try {
                    assertNull(first.awaitItem())
                    assertNull(second.awaitItem())
                } finally {
                    first.cancelAndIgnoreRemainingEvents()
                    second.cancelAndIgnoreRemainingEvents()
                }
            }

            sourceOfTruth.write(keyA, "value")
            sourceOfTruth.reader(keyA).test {
                assertEquals("value", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun writeFailure_rollsBackAndEchoesNothing(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val sourceOfTruth =
                sourceOfTruth(database) { key, value ->
                    dao.upsert(row(key, value))
                    throw IllegalStateException("write failed")
                }

            sourceOfTruth.reader(keyA).test {
                assertNull(awaitItem())

                assertFailsWith<IllegalStateException> {
                    sourceOfTruth.write(keyA, "rolled-back")
                }
                runCurrent()
                expectNoEvents()
                assertNull(dao.row(keyA.namespace.value, keyA.canonicalId()).first())

                dao.upsert(row(keyA, "committed"))
                assertEquals("committed", awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun explicitCancellationFromMutation_rollsBackAndThrows(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val cancellation = CancellationException("mutation cancelled itself")
            val sourceOfTruth =
                sourceOfTruth(database) { key, value ->
                    dao.upsert(row(key, value))
                    throw cancellation
                }

            assertFailsWith<CancellationException> {
                sourceOfTruth.write(keyA, "rolled-back")
            }
            assertNull(dao.row(keyA.namespace.value, keyA.canonicalId()).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun twoAdaptersOnSameDatabase_nestedAndCompetingWrites_doNotInvertLocks(): TestResult =
        runTest {
            val database = createTestDatabase()
            val allowNestedWrite = CompletableDeferred<Unit>()
            var outerTransaction: Deferred<Unit>? = null
            var competingWrite: Deferred<Unit>? = null
            try {
                val dao = database.kitRowDao()
                val adapterA = sourceOfTruth(database)
                val adapterB = sourceOfTruth(database)
                val outerHasWriter = CompletableDeferred<Unit>()

                outerTransaction =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        adapterA.withTransaction {
                            outerHasWriter.complete(Unit)
                            allowNestedWrite.await()
                            adapterB.write(keyA, "nested")
                        }
                    }
                outerHasWriter.await()

                competingWrite =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        adapterB.write(keyB, "competing")
                    }
                allowNestedWrite.complete(Unit)

                outerTransaction.await()
                competingWrite.await()

                assertEquals(
                    "nested",
                    dao.row(keyA.namespace.value, keyA.canonicalId()).first()?.payload,
                )
                assertEquals(
                    "competing",
                    dao.row(keyB.namespace.value, keyB.canonicalId()).first()?.payload,
                )
            } finally {
                allowNestedWrite.complete(Unit)
                outerTransaction?.cancel()
                competingWrite?.cancel()
                outerTransaction?.join()
                competingWrite?.join()
                database.close()
            }
        }

    @Test
    fun readerJoiningFailedAllocation_equalRewriteEmitsExactlyOnce(): TestResult = runTest {
        val database = createTestDatabase()
        val releaseFailure = CompletableDeferred<Unit>()
        var failedWrite: Deferred<Result<Unit>>? = null
        try {
            val failedWriterEntered = CompletableDeferred<Unit>()
            val rows = MutableStateFlow<String?>("same")
            val sourceOfTruth =
                flowProbeSourceOfTruth(database, rows) { _, value ->
                    if (value == "fail") {
                        failedWriterEntered.complete(Unit)
                        releaseFailure.await()
                        error("allocation failed")
                    }
                }

            sourceOfTruth.reader(keyA).test {
                assertEquals("same", awaitItem())

                failedWrite =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { sourceOfTruth.write(keyA, "fail") }
                    }
                failedWriterEntered.await()

                sourceOfTruth.reader(keyA).test {
                    assertEquals("same", awaitItem())

                    releaseFailure.complete(Unit)
                    assertTrue(checkNotNull(failedWrite).await().isFailure)
                    runCurrent()
                    expectNoEvents()

                    sourceOfTruth.write(keyA, "same")
                    assertEquals("same", awaitItem())
                    expectNoEvents()

                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFailure.complete(Unit)
            failedWrite?.cancel()
            failedWrite?.join()
            database.close()
        }
    }

    @Test
    fun readerJoiningSuccessfulAllocation_equalRewriteEmitsExactlyOnce(): TestResult = runTest {
        val database = createTestDatabase()
        val releaseWrite = CompletableDeferred<Unit>()
        var equalRewrite: Deferred<Result<Unit>>? = null
        try {
            val writerEntered = CompletableDeferred<Unit>()
            val rows = MutableStateFlow<String?>("same")
            val sourceOfTruth =
                flowProbeSourceOfTruth(database, rows) { _, _ ->
                    writerEntered.complete(Unit)
                    releaseWrite.await()
                }

            sourceOfTruth.reader(keyA).test {
                assertEquals("same", awaitItem())

                equalRewrite =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { sourceOfTruth.write(keyA, "same") }
                    }
                writerEntered.await()

                sourceOfTruth.reader(keyA).test {
                    assertEquals("same", awaitItem())

                    releaseWrite.complete(Unit)
                    assertTrue(checkNotNull(equalRewrite).await().isSuccess)
                    assertEquals("same", awaitItem())
                    expectNoEvents()

                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseWrite.complete(Unit)
            equalRewrite?.cancel()
            equalRewrite?.join()
            database.close()
        }
    }

    @Test
    fun cancellationWhileAdmittedAfterDaoWrite_commitsAndReturnsNormally(): TestResult = runTest {
        val database = createTestDatabase()
        val releaseWriter = CompletableDeferred<Unit>()
        var write: Deferred<Unit>? = null
        val boundary = CompletableDeferred<Result<Unit>>()
        try {
            val dao = database.kitRowDao()
            val daoWriteCompleted = CompletableDeferred<Unit>()
            val sourceOfTruth =
                sourceOfTruth(database) { key, value ->
                    dao.upsert(row(key, value))
                    daoWriteCompleted.complete(Unit)
                    releaseWriter.await()
                }

            write =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    boundary.complete(
                        runCatching { sourceOfTruth.write(keyA, "committed") },
                    )
                }
            daoWriteCompleted.await()
            write.cancel()
            releaseWriter.complete(Unit)

            write.join()
            assertTrue(
                boundary.await().isSuccess,
                "cancellation after admission must not escape a committed write",
            )
            assertEquals(
                "committed",
                dao.row(keyA.namespace.value, keyA.canonicalId()).first()?.payload,
            )
        } finally {
            releaseWriter.complete(Unit)
            write?.cancel()
            write?.join()
            database.close()
        }
    }

    @Test
    fun cancelledBackpressuredWrite_publishesEchoAndDoesNotWedgeGate(): TestResult = runTest {
        val databasePath = newTempDatabasePath()
        val database = openTestDatabase(databasePath)
        val externalDatabase = openTestDatabase(databasePath)
        val releaseCollector = CompletableDeferred<Unit>()
        var collector: Job? = null
        var externalCandidateObservation: Deferred<Unit>? = null
        var releaseWrite: Deferred<Unit>? = null
        var queuedWrite: Deferred<Unit>? = null
        var candidateWrite: Deferred<Unit>? = null
        val candidateBoundary = CompletableDeferred<Result<Unit>>()
        try {
            val dao = database.kitRowDao()
            val externalDao = externalDatabase.kitRowDao()
            assertNull(dao.row(keyA.namespace.value, keyA.canonicalId()).first())
            assertNull(externalDao.row(keyA.namespace.value, keyA.canonicalId()).first())
            val fillMergeBuffer = CompletableDeferred<Unit>()
            val mergeBufferFilled = CompletableDeferred<Unit>()
            val queryExternalValue = CompletableDeferred<Unit>()
            val sourceOfTruth =
                backpressureProbeSourceOfTruth(
                    database = database,
                    fillMergeBuffer = fillMergeBuffer,
                    mergeBufferFilled = mergeBufferFilled,
                    queryExternalValue = queryExternalValue,
                )
            val initialObserved = CompletableDeferred<Unit>()
            val blockedEchoObserved = CompletableDeferred<Unit>()
            val candidateEchoObserved = CompletableDeferred<Unit>()
            val externalChangeObserved = CompletableDeferred<Unit>()
            val blockingValue = EchoProbeValue("block")
            val candidateValue = EchoProbeValue("candidate")

            collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sourceOfTruth.reader(keyA).collect { value ->
                        when {
                            value == null -> initialObserved.complete(Unit)
                            value === blockingValue -> {
                                blockedEchoObserved.complete(Unit)
                                releaseCollector.await()
                            }
                            value === candidateValue -> candidateEchoObserved.complete(Unit)
                            value.payload == "external" -> externalChangeObserved.complete(Unit)
                        }
                    }
                }
            initialObserved.await()

            sourceOfTruth.write(keyA, blockingValue)
            blockedEchoObserved.await()
            fillMergeBuffer.complete(Unit)
            mergeBufferFilled.await()

            sourceOfTruth.write(keyA, EchoProbeValue("in-flight"))
            runCurrent()
            repeat(64) { index ->
                sourceOfTruth.write(keyA, EchoProbeValue("buffer-$index"))
            }

            externalCandidateObservation =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    while (
                        externalDao
                            .row(keyA.namespace.value, keyA.canonicalId())
                            .first()
                            ?.payload != "candidate"
                    ) {
                        yield()
                    }
                }
            candidateWrite =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    candidateBoundary.complete(
                        runCatching { sourceOfTruth.write(keyA, candidateValue) },
                    )
                }
            externalCandidateObservation.await()
            runCurrent()
            assertFalse(
                candidateWrite.isCompleted,
                "the candidate echo must be backpressured by the saturated buffer",
            )

            candidateWrite.cancel()
            runCurrent()
            queuedWrite =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    sourceOfTruth.write(keyB, EchoProbeValue("queued"))
                }
            releaseWrite =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    dao.upsert(row(keyB, "release"))
                    releaseCollector.complete(Unit)
                }
            releaseWrite.await()
            candidateEchoObserved.await()
            candidateWrite.join()
            assertTrue(
                candidateBoundary.await().isSuccess,
                "cancellation after the durable commit must not throw from write",
            )
            assertEquals(
                "candidate",
                dao.row(keyA.namespace.value, keyA.canonicalId()).first()?.payload,
                "the candidate transaction must remain committed after writer cancellation",
            )

            queuedWrite.await()
            assertEquals(
                "queued",
                dao.row(keyB.namespace.value, keyB.canonicalId()).first()?.payload,
            )
            externalDao.upsert(row(keyA, "external"))
            queryExternalValue.complete(Unit)
            externalChangeObserved.await()
        } finally {
            releaseCollector.complete(Unit)
            candidateWrite?.cancel()
            externalCandidateObservation?.cancel()
            queuedWrite?.cancel()
            releaseWrite?.cancel()
            collector?.cancel()
            externalDatabase.close()
            database.close()
            candidateWrite?.join()
            externalCandidateObservation?.join()
            queuedWrite?.join()
            releaseWrite?.join()
            collector?.join()
        }
    }

    private fun sourceOfTruth(
        database: Store6RoomTestDatabase,
        rowWriter: (suspend (RoomKitKey, String) -> Unit)? = null,
    ): RoomSourceOfTruth<RoomKitKey, String> {
        val dao = database.kitRowDao()
        val writer: suspend (RoomKitKey, String) -> Unit =
            rowWriter ?: { key, value ->
                dao.upsert(row(key, value))
            }
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                dao.row(key.namespace.value, key.canonicalId()).map { it?.payload }
            },
            rowWriter = writer,
            rowDeleter = { key ->
                dao.delete(key.namespace.value, key.canonicalId())
            },
            namespaceDeleter = { namespace ->
                dao.deleteNamespace(namespace.value)
            },
            allDeleter = {
                dao.deleteAll()
            },
        )
    }

    private fun flowProbeSourceOfTruth(
        database: Store6RoomTestDatabase,
        rows: MutableStateFlow<String?>,
        rowWriter: suspend (RoomKitKey, String) -> Unit,
    ): RoomSourceOfTruth<RoomKitKey, String> =
        RoomSourceOfTruth(
            database = database,
            rowReader = { rows },
            rowWriter = rowWriter,
            rowDeleter = {},
            namespaceDeleter = {},
            allDeleter = {},
        )

    private fun backpressureProbeSourceOfTruth(
        database: Store6RoomTestDatabase,
        fillMergeBuffer: CompletableDeferred<Unit>,
        mergeBufferFilled: CompletableDeferred<Unit>,
        queryExternalValue: CompletableDeferred<Unit>,
    ): RoomSourceOfTruth<RoomKitKey, EchoProbeValue> {
        val dao = database.kitRowDao()
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                kotlinx.coroutines.flow.flow {
                    suspend fun currentValue(): EchoProbeValue? =
                        dao
                            .row(key.namespace.value, key.canonicalId())
                            .first()
                            ?.let { EchoProbeValue(it.payload) }

                    emit(currentValue())
                    fillMergeBuffer.await()
                    repeat(64) { index ->
                        emit(EchoProbeValue("database-buffer-$index"))
                    }
                    mergeBufferFilled.complete(Unit)
                    emit(EchoProbeValue("database-buffer-in-flight"))
                    queryExternalValue.await()
                    emit(currentValue())
                }
            },
            rowWriter = { key, value ->
                dao.upsert(row(key, value.payload))
            },
            rowDeleter = { key ->
                dao.delete(key.namespace.value, key.canonicalId())
            },
            namespaceDeleter = { namespace ->
                dao.deleteNamespace(namespace.value)
            },
            allDeleter = {
                dao.deleteAll()
            },
        )
    }

    private fun equalityProbeSourceOfTruth(
        database: Store6RoomTestDatabase,
        equalityObserved: CompletableDeferred<Unit>,
    ): RoomSourceOfTruth<RoomKitKey, EqualityProbeValue> {
        val dao = database.kitRowDao()
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                dao.row(key.namespace.value, key.canonicalId()).map { entity ->
                    entity?.let { EqualityProbeValue(it.payload, equalityObserved) }
                }
            },
            rowWriter = { key, value ->
                dao.upsert(row(key, value.payload))
            },
            rowDeleter = { key ->
                dao.delete(key.namespace.value, key.canonicalId())
            },
            namespaceDeleter = { namespace ->
                dao.deleteNamespace(namespace.value)
            },
            allDeleter = {
                dao.deleteAll()
            },
        )
    }

    private fun row(
        key: RoomKitKey,
        value: String,
    ): KitRowEntity =
        KitRowEntity(
            namespace = key.namespace.value,
            id = key.canonicalId(),
            payload = value,
        )

    private class EqualityProbeValue(
        val payload: String,
        private val equalityObserved: CompletableDeferred<Unit>,
    ) {
        override fun equals(other: Any?): Boolean {
            val equal = other is EqualityProbeValue && payload == other.payload
            if (equal) {
                equalityObserved.complete(Unit)
            }
            return equal
        }

        override fun hashCode(): Int = payload.hashCode()
    }

    private data class EchoProbeValue(
        val payload: String,
    )
}

// 017 residual-deadline repair: Turbine's 3s default nested inside the 25s shadow; raise the
// Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15).
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
