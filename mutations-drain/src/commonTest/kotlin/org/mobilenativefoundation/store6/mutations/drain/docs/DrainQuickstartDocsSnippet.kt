@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain.docs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.drain.DrainFixture
import org.mobilenativefoundation.store6.mutations.drain.DrainTestKey
import org.mobilenativefoundation.store6.mutations.drain.InProcessDrainScheduler
import org.mobilenativefoundation.store6.mutations.drain.waitUntilCurrent
import org.mobilenativefoundation.store6.mutations.drain.mutationDrainCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class DrainQuickstartDocsSnippet {
    @Test
    fun watchAndManualActivationDrainPendingWrite() = runTest {
        val fixture = DrainFixture()
        val users = fixture.openStore()
        try {
            users.mutate(DrainTestKey("quickstart"), fixture.appendRef, "+done")

            quickstart(users, backgroundScope)
            waitUntilCurrent { users.pendingWrites().isEmpty() }

            assertEquals(emptyList(), users.pendingWrites())
            assertEquals(listOf("+done"), fixture.backend.receivedPushes)
        } finally {
            users.close()
        }
    }
}

private suspend fun TestScope.quickstart(
    users: MutationStore<DrainTestKey, String>,
    scope: CoroutineScope,
) {
    // docs:snippet:mutations-drain-quickstart
    @OptIn(ExperimentalStoreApi::class)   // required: the whole module is experimental
    val coordinator = mutationDrainCoordinator(InProcessDrainScheduler(scope))
    coordinator.register("com.example.users", users)
    val watch = scope.launch { coordinator.watch("com.example.users") }
    coordinator.runActivation("com.example.users")
    // docs:snippet:end
    testScheduler.runCurrent()
    watch.cancelAndJoin()
    coordinator.close()
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
