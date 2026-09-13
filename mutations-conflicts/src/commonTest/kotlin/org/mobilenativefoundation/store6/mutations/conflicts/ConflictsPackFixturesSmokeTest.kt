@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ConflictsPackFixturesSmokeTest {

    @Test
    fun stringStore_mutateAndDrain_recordsOneAckAndRetires() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<String>()
        val clock = TestWallClock()
        val store = openStringStore(storage, server, clock)
        val key = ConflictsPackKey("smoke")
        try {
            store.mutate(key, stringUpsert, "value")
            store.drain(key)
            val clientId = capturedClientId(server, storage)
            storage.transaction { transaction ->
                assertEquals(1, transaction.acks(clientId).size)
                assertEquals(
                    MutationExecutionPhase.RETIRED,
                    transaction.executions(clientId).single().phase,
                )
            }
        } finally {
            store.close()
        }
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
