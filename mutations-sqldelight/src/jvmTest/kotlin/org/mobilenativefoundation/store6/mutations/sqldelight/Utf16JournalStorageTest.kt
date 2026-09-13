@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.mutations.MutationFailureKind
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import kotlin.test.Test
import kotlin.test.assertEquals

internal class Utf16JournalStorageTest {
    @Test
    fun validUtf8FailureBoundsRemainValidInUtf16Database() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val harness = JournalHarness(driver)
            harness.executeRaw("PRAGMA encoding = 'UTF-16'")
            val storage = harness.storage()
            storage.transaction { transaction ->
                transaction.insertClient(
                    MutationClientRecord(
                        recordVersion = 1,
                        clientId = "client",
                        lastAllocatedSequence = 0L,
                        retiredThroughSequence = 0L,
                        serverConfirmedRetiredThroughSequence = 0L,
                        createdAt = 1L,
                    ),
                )
                transaction.advanceClient(
                    MutationClientRecord(
                        recordVersion = 1,
                        clientId = "client",
                        lastAllocatedSequence = 1L,
                        retiredThroughSequence = 0L,
                        serverConfirmedRetiredThroughSequence = 0L,
                        createdAt = 1L,
                    ),
                )
                transaction.insertIntent(
                    recordVersion = 1,
                    clientId = "client",
                    clientSequence = 1L,
                    mutationId = "mutation-1",
                    namespace = "items",
                    canonicalId = "one",
                    mutatorId = "upsert",
                    mutatorVersion = 1,
                    argsBlob = byteArrayOf(1),
                    idempotencyRoot = "client:1",
                    createdAt = 2L,
                )
                transaction.insertExecution(
                    MutationExecutionRecord(
                        clientId = "client",
                        clientSequence = 1L,
                        phase = MutationExecutionPhase.UNPREPARED,
                        currentGeneration = 0,
                        attempt = 0,
                        lastAttemptAt = null,
                        activeFailureId = null,
                        retiredAt = null,
                    ),
                )
                transaction.appendFailure(
                    clientId = "client",
                    clientSequence = 1L,
                    generation = 0,
                    kind = MutationFailureKind.PERSISTENCE,
                    detail = "d".repeat(128),
                    message = "m".repeat(1_024),
                    occurredAt = 3L,
                )
            }

            val failure = storage.transaction { it.failures("client").single() }
            assertEquals(128, failure.detail.encodeToByteArray().size)
            assertEquals(1_024, failure.message.encodeToByteArray().size)
        } finally {
            driver.close()
        }
    }
}
