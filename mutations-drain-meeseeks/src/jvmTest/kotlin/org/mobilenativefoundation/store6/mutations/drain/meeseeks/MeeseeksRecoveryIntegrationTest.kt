@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.Meeseeks
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskPayload
import dev.mattramotar.meeseeks.runtime.TaskPreconditions
import dev.mattramotar.meeseeks.runtime.TaskRequest
import dev.mattramotar.meeseeks.runtime.TaskResult
import dev.mattramotar.meeseeks.runtime.TaskSchedule
import dev.mattramotar.meeseeks.runtime.TaskStatus
import dev.mattramotar.meeseeks.runtime.Worker
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints
import org.mobilenativefoundation.store6.mutations.drain.DrainRequest
import org.mobilenativefoundation.store6.mutations.drain.MutationDrainCoordinator
import org.mobilenativefoundation.store6.mutations.drain.mutationDrainCoordinator

class MeeseeksRecoveryIntegrationTest {
    private val workingDirectory: Path = Path.of("").toAbsolutePath()
    private val databaseFiles =
        listOf(
            "meeseeks.db",
            "meeseeks.db-journal",
            "meeseeks.db-wal",
            "meeseeks.db-shm",
            "quartz-scheduler.db",
            "quartz-scheduler.db-journal",
            "quartz-scheduler.db-wal",
            "quartz-scheduler.db-shm",
        ).map(workingDirectory::resolve)

    @BeforeTest
    fun clearMeeseeksDatabaseBeforeTest() {
        databaseFiles.forEach(Files::deleteIfExists)
    }

    @AfterTest
    fun clearMeeseeksDatabaseAfterTest() {
        databaseFiles.forEach { path ->
            runCatching { Files.deleteIfExists(path) }
        }
    }

    @Test
    fun taskStatusDistinguishesPendingRunningTerminal(): Unit = runBlocking {
        val harness = RecoveryHarness.initialize()
        val fixture = AdapterJvmFixture()
        val store = fixture.openStore()
        val storeName = uniqueRecoveryStoreName("status")
        var pushGate: CompletableDeferred<Unit>? = null
        harness.coordinator.register(storeName, store, adapterJvmDrainPolicy())
        try {
            harness.scheduler.schedule(recoveryRequest(storeName, 30.seconds))
            val taskId = checkNotNull(harness.scheduler.trackedTaskIds[storeName])
            assertEquals(TaskStatus.Pending, harness.manager.getTaskStatus(taskId))

            pushGate = fixture.backend.gateNextPush()
            store.mutate(AdapterJvmKey("status-key"), fixture.appendRef, "+status")
            harness.scheduler.schedule(recoveryRequest(storeName, Duration.ZERO))

            awaitRecoveryCondition {
                harness.manager.getTaskStatus(taskId) == TaskStatus.Running
            }
            assertEquals(TaskStatus.Running, harness.manager.getTaskStatus(taskId))

            pushGate.complete(Unit)
            awaitRecoveryCondition {
                harness.manager.getTaskStatus(taskId) is TaskStatus.Finished
            }
            assertIs<TaskStatus.Finished.Completed>(harness.manager.getTaskStatus(taskId))
        } finally {
            pushGate?.complete(Unit)
            harness.close()
            store.close()
        }
    }

    @Test
    fun unknownRegistrationRowsAreSurvivable(): Unit = runBlocking {
        val phaseOne = RecoveryHarness.initialize(registerLegacyPayload = true)
        val legacyTaskId =
            phaseOne.manager.schedule(
                TaskRequest(
                    payload = LegacyPayload("legacy"),
                    preconditions =
                        TaskPreconditions(
                            requiresNetwork = false,
                            requiresCharging = false,
                        ),
                    schedule = TaskSchedule.OneTime(initialDelay = 5.minutes),
                ),
            )
        assertEquals(TaskStatus.Pending, phaseOne.manager.getTaskStatus(legacyTaskId))
        phaseOne.abandon()

        val phaseTwo = RecoveryHarness.initialize()
        val storeName = uniqueRecoveryStoreName("unknown-registration")
        try {
            phaseTwo.scheduler.schedule(recoveryRequest(storeName, 5.minutes))

            val recoveredTaskId = checkNotNull(phaseTwo.scheduler.trackedTaskIds[storeName])
            assertNotEquals(legacyTaskId, recoveredTaskId)
            assertEquals(TaskStatus.Pending, phaseTwo.manager.getTaskStatus(recoveredTaskId))
        } finally {
            phaseTwo.close()
            phaseOne.manager.cancelAll()
        }
    }

    @Test
    fun terminalRowsRemainBounded(): Unit = runBlocking {
        val harness = RecoveryHarness.initialize()
        val fixture = AdapterJvmFixture()
        val store = fixture.openStore()
        val storeName = uniqueRecoveryStoreName("retention")
        harness.coordinator.register(storeName, store, adapterJvmDrainPolicy())
        var rowCountAfterTenCycles = 0
        try {
            repeat(20) { cycle ->
                store.mutate(
                    AdapterJvmKey("retention-key"),
                    fixture.appendRef,
                    "+$cycle",
                )
                harness.scheduler.schedule(recoveryRequest(storeName, Duration.ZERO))
                val taskId = checkNotNull(harness.scheduler.trackedTaskIds[storeName])

                awaitRecoveryCondition {
                    harness.manager.getTaskStatus(taskId) is TaskStatus.Finished &&
                        store.pendingWrites().isEmpty()
                }
                assertIs<TaskStatus.Finished.Completed>(harness.manager.getTaskStatus(taskId))
                if (cycle == 9) {
                    rowCountAfterTenCycles = harness.manager.listTasks().size
                }
            }

            val rowCountAfterTwentyCycles = harness.manager.listTasks().size
            assertTrue(
                rowCountAfterTwentyCycles <= rowCountAfterTenCycles + 2,
                "Terminal task rows grew from $rowCountAfterTenCycles after 10 cycles " +
                    "to $rowCountAfterTwentyCycles after 20 cycles.",
            )
        } finally {
            harness.close()
            store.close()
        }
    }
}

@Serializable
private class LegacyPayload(
    val value: String,
) : TaskPayload

private class LegacyWorker(
    appContext: AppContext,
) : Worker<LegacyPayload>(appContext) {
    override suspend fun run(
        payload: LegacyPayload,
        context: RuntimeContext,
    ): TaskResult = TaskResult.Success
}

private class RecoveryHarness private constructor(
    internal val manager: BGTaskManager,
    internal val scheduler: MeeseeksDrainScheduler,
    internal val coordinator: MutationDrainCoordinator,
) {
    internal fun abandon() {
        coordinator.close()
    }

    internal fun close() {
        manager.cancelAll()
        coordinator.close()
    }

    companion object {
        internal fun initialize(
            registerLegacyPayload: Boolean = false,
        ): RecoveryHarness {
            lateinit var schedulerReference: MeeseeksDrainScheduler
            val manager =
                Meeseeks.initialize(AdapterJvmAppContext()) {
                    minBackoff(50.milliseconds)
                    register<StoreDrainPayload> { appContext ->
                        StoreDrainWorker(appContext, schedulerReference)
                    }
                    if (registerLegacyPayload) {
                        register<LegacyPayload> { appContext ->
                            LegacyWorker(appContext)
                        }
                    }
                }
            schedulerReference = MeeseeksDrainScheduler { manager }
            val coordinator = mutationDrainCoordinator(schedulerReference)
            return RecoveryHarness(manager, schedulerReference, coordinator)
        }
    }
}

private fun recoveryRequest(
    storeName: String,
    delay: Duration,
): DrainRequest =
    DrainRequest(
        storeName = storeName,
        constraints =
            DrainConstraints(
                requiresNetwork = false,
                requiresCharging = false,
            ),
        earliestDelay = delay,
    )

private fun uniqueRecoveryStoreName(prefix: String): String =
    "$prefix-${UUID.randomUUID().toString().take(8)}"

private suspend fun awaitRecoveryCondition(
    timeout: Duration = 30.seconds,
    poll: Duration = 50.milliseconds,
    condition: suspend () -> Boolean,
) {
    withTimeout(timeout) {
        while (!condition()) delay(poll)
    }
}
