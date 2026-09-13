@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.room

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withTimeoutOrNull
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class RoomTransactionalSourceOfTruthTest {
    private val keyA = RoomKitKey(StoreNamespace("users"), "a")

    @Test
    fun withTransaction_writeThenThrow_rollsBackRow(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val dao = database.kitRowDao()

            assertFailsWith<IllegalStateException> {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, "v1")
                    error("boom")
                }
            }

            assertNull(dao.row(keyA.namespace.value, keyA.canonicalId()).first())
            assertNull(sourceOfTruth.reader(keyA).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun withTransaction_valueAndMeta_commitAtomically(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val bookkeeper = RoomBookkeeper(database, database.store6BookkeeperDao())

            sourceOfTruth.withTransaction {
                sourceOfTruth.write(keyA, "v1")
                bookkeeper.recordSuccess(
                    keyA,
                    TestStoreMeta(writtenAtEpochMillis = 1L, etag = "e1"),
                )
            }

            assertEquals(
                "v1",
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first()
                    ?.payload,
            )
            assertEquals("e1", bookkeeper.status(keyA)?.meta?.etag)

            assertFailsWith<IllegalStateException> {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, "v2")
                    bookkeeper.recordSuccess(
                        keyA,
                        TestStoreMeta(writtenAtEpochMillis = 2L, etag = "e2"),
                    )
                    error("boom after value and metadata")
                }
            }

            assertEquals(
                "v1",
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first()
                    ?.payload,
            )
            assertEquals("e1", bookkeeper.status(keyA)?.meta?.etag)
        } finally {
            database.close()
        }
    }

    @Test
    fun withTransaction_throwBetweenValueAndMeta_rollsBackBoth(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val bookkeeper = RoomBookkeeper(database, database.store6BookkeeperDao())

            assertFailsWith<IllegalStateException> {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, "v1")
                    error("boom before metadata")
                }
            }

            assertNull(
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first(),
            )
            assertNull(bookkeeper.status(keyA))
        } finally {
            database.close()
        }
    }

    @Test
    fun withTransaction_returnsBlockResult(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)

            val result = sourceOfTruth.withTransaction { "block-result" }

            assertEquals("block-result", result)
        } finally {
            database.close()
        }
    }

    @Test
    fun nestedWrite_outerRollback_neverPublishesEcho(): TestResult = runTest {
        val database = createTestDatabase()
        val releaseOuter = CompletableDeferred<Unit>()
        var transaction: Deferred<Result<Unit>>? = null
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val nestedWriteReturned = CompletableDeferred<Unit>()

            sourceOfTruth.reader(keyA).test {
                assertNull(awaitItem())

                transaction =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            sourceOfTruth.withTransaction {
                                sourceOfTruth.write(keyA, "rolled-back")
                                nestedWriteReturned.complete(Unit)
                                releaseOuter.await()
                                error("rollback outer transaction")
                            }
                        }
                    }
                nestedWriteReturned.await()
                runCurrent()
                expectNoEvents()

                releaseOuter.complete(Unit)
                assertTrue(checkNotNull(transaction).await().isFailure)
                runCurrent()
                expectNoEvents()
                assertNull(
                    database
                        .kitRowDao()
                        .row(keyA.namespace.value, keyA.canonicalId())
                        .first(),
                )

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseOuter.complete(Unit)
            transaction?.cancel()
            transaction?.join()
            database.close()
        }
    }

    @Test
    fun nestedWrite_outerCommit_publishesEchoOnlyAfterCommit(): TestResult = runTest {
        val database = createTestDatabase()
        val allowCommit = CompletableDeferred<Unit>()
        var transaction: Deferred<Unit>? = null
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val nestedWriteReturned = CompletableDeferred<Unit>()

            sourceOfTruth.reader(keyA).test {
                assertNull(awaitItem())

                transaction =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        sourceOfTruth.withTransaction {
                            sourceOfTruth.write(keyA, "transient")
                            sourceOfTruth.write(keyA, "committed")
                            nestedWriteReturned.complete(Unit)
                            allowCommit.await()
                        }
                    }
                nestedWriteReturned.await()
                runCurrent()
                expectNoEvents()

                allowCommit.complete(Unit)
                checkNotNull(transaction).await()
                assertEquals("committed", awaitItem())
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            allowCommit.complete(Unit)
            transaction?.cancel()
            transaction?.join()
            database.close()
        }
    }

    @Test
    fun caughtNestedTransactionRollback_preservesOuterValueAndEcho(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)

            sourceOfTruth.reader(keyA).test {
                assertNull(awaitItem())

                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, "outer")
                    assertFailsWith<IllegalStateException> {
                        sourceOfTruth.withTransaction {
                            sourceOfTruth.write(keyA, "rolled-back-nested")
                            error("rollback nested savepoint")
                        }
                    }
                }

                assertEquals("outer", awaitItem())
                expectNoEvents()
                assertEquals(
                    "outer",
                    database
                        .kitRowDao()
                        .row(keyA.namespace.value, keyA.canonicalId())
                        .first()
                        ?.payload,
                )

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun crossAdapterNestedWrite_outerRollbackPublishesNoEcho(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val dao = database.kitRowDao()
            val adapterA = sourceOfTruth(database)
            val adapterB = sourceOfTruth(database)
            dao.upsert(
                KitRowEntity(
                    namespace = keyA.namespace.value,
                    id = keyA.canonicalId(),
                    payload = "prior",
                ),
            )

            adapterB.reader(keyA).test {
                assertEquals("prior", awaitItem())

                assertFailsWith<IllegalStateException> {
                    adapterA.withTransaction {
                        adapterB.write(keyA, "rolled-back")
                        error("rollback adapter A transaction")
                    }
                }
                runCurrent()
                expectNoEvents()
                assertEquals(
                    "prior",
                    dao.row(keyA.namespace.value, keyA.canonicalId()).first()?.payload,
                )

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun crossDatabaseNestedWrite_failsFastInsteadOfInvertingStripeOrder(): TestResult = runTest {
        val databaseA = createTestDatabase()
        val databaseB = createTestDatabase()
        try {
            val adapterA = sourceOfTruth(databaseA)
            val adapterB = sourceOfTruth(databaseB)

            val failure =
                assertFailsWith<IllegalStateException> {
                    withTimeoutOrNull(10.seconds) {
                        adapterA.withTransaction {
                            adapterB.write(keyA, "nested")
                        }
                    }
                }
            assertTrue(
                failure.message.orEmpty().contains("Cannot nest database admission"),
                "the inner write must fail fast on cross-database nesting, got: ${failure.message}",
            )
            assertNull(
                databaseB.kitRowDao().row(keyA.namespace.value, keyA.canonicalId()).first(),
            )
        } finally {
            databaseA.close()
            databaseB.close()
        }
    }

    @Test
    fun sameDatabaseNestedWrite_throughDifferentAdapterInstance_stillJoinsOuterTransaction(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val outer = sourceOfTruth(database)
            val inner = sourceOfTruth(database)
            val dao = database.kitRowDao()

            outer.withTransaction {
                inner.write(keyA, "joined")
            }
            assertEquals("joined", dao.row(keyA.namespace.value, keyA.canonicalId()).first()?.payload)
        } finally {
            database.close()
        }
    }

    @Test
    fun launchedChild_failsFastInsteadOfWaitingOnParentsDatabaseAdmission(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val childWriterEntered = CompletableDeferred<Unit>()
            val sourceOfTruth =
                sourceOfTruth(database) { key, value ->
                    childWriterEntered.complete(Unit)
                    database
                        .kitRowDao()
                        .upsert(KitRowEntity(key.namespace.value, key.canonicalId(), value))
                }

            sourceOfTruth.withTransaction {
                coroutineScope {
                    val child =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            runCatching {
                                sourceOfTruth.write(keyA, "child")
                            }
                        }
                    runCurrent()
                    assertTrue(
                        child.isCompleted,
                        "a child coroutine must fail instead of waiting on its parent's admission",
                    )
                    val failure = assertIs<IllegalStateException>(child.await().exceptionOrNull())
                    assertEquals(
                        "Room transaction mutations must remain sequential in the owning coroutine",
                        failure.message,
                    )
                    assertFalse(
                        childWriterEntered.isCompleted,
                        "a child coroutine must not enter the inherited Room transaction",
                    )
                }
            }

            assertNull(
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first(),
            )
        } finally {
            database.close()
        }
    }
}

private fun sourceOfTruth(
    database: Store6RoomTestDatabase,
    rowWriter: (suspend (RoomKitKey, String) -> Unit)? = null,
): RoomSourceOfTruth<RoomKitKey, String> {
    val dao = database.kitRowDao()
    return RoomSourceOfTruth(
        database = database,
        rowReader = { key ->
            dao.row(key.namespace.value, key.canonicalId()).map { it?.payload }
        },
        rowWriter =
            rowWriter ?: { key, value ->
                dao.upsert(KitRowEntity(key.namespace.value, key.canonicalId(), value))
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

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
