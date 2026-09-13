@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.Meeseeks
import dev.mattramotar.meeseeks.runtime.TaskStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.mutations.drain.DrainActivationStarted
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints
import org.mobilenativefoundation.store6.mutations.drain.DrainPassFailed
import org.mobilenativefoundation.store6.mutations.drain.DrainRequest
import org.mobilenativefoundation.store6.mutations.drain.MutationDrainCoordinator
import org.mobilenativefoundation.store6.mutations.drain.mutationDrainCoordinator

class MeeseeksExecutionIntegrationTest {
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
    fun scheduleFromInsideRunningWorkerFiresLater(): Unit = runBlocking {
        val fixture = AdapterJvmFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        val harness = MeeseeksHarness()
        val storeName = uniqueStoreName("inside-worker")
        harness.coordinator.register(storeName, store, adapterJvmDrainPolicy())
        try {
            store.mutate(AdapterJvmKey("key"), fixture.appendRef, "+scheduled")

            harness.scheduler.schedule(immediateRequest(storeName))
            awaitCondition {
                fixture.backend.pushAttempts.get() >= 1
            }
            fixture.backend.offline = false

            awaitCondition {
                fixture.backend.receivedPushes.size == 1 && store.pendingWrites().isEmpty()
            }
            assertEquals(listOf("+scheduled"), fixture.backend.receivedPushes)
            assertTrue(fixture.backend.pushAttempts.get() >= 2)
        } finally {
            harness.close()
            store.close()
        }
    }

    @Test
    fun successProducesNoFurtherActivations(): Unit = runBlocking {
        val fixture = AdapterJvmFixture()
        val store = fixture.openStore()
        val harness = MeeseeksHarness()
        val storeName = uniqueStoreName("success")
        val activationCount = AtomicInteger()
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventCollector =
            eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
                harness.coordinator.events
                    .filterIsInstance<DrainActivationStarted>()
                    .collect { event ->
                        if (event.storeName == storeName) activationCount.incrementAndGet()
                    }
            }
        harness.coordinator.register(storeName, store, adapterJvmDrainPolicy())
        try {
            store.mutate(AdapterJvmKey("key"), fixture.appendRef, "+cleared")
            harness.scheduler.schedule(immediateRequest(storeName))

            awaitCondition {
                fixture.backend.receivedPushes.size == 1 &&
                    store.pendingWrites().isEmpty() &&
                    harness.activeTasks(storeName).isEmpty()
            }
            assertEquals(1, activationCount.get())

            delay(5.seconds)

            assertEquals(1, activationCount.get())
            assertTrue(harness.activeTasks(storeName).isEmpty())
        } finally {
            eventCollector.cancelAndJoin()
            eventScope.cancel()
            harness.close()
            store.close()
        }
    }

    @Test
    fun transientRetriesBoundedByConfig(): Unit = runBlocking {
        val harness = MeeseeksHarness(maxRetryCount = 2)
        val storeName = uniqueStoreName("unregistered")
        val failures = AtomicInteger()
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventCollector =
            eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
                harness.coordinator.events
                    .filterIsInstance<DrainPassFailed>()
                    .collect { event ->
                        if (event.storeName == storeName) failures.incrementAndGet()
                    }
            }
        try {
            harness.scheduler.schedule(immediateRequest(storeName))
            val taskId = checkNotNull(harness.scheduler.trackedTaskIds[storeName])

            awaitCondition(timeout = 80.seconds) {
                harness.manager.getTaskStatus(taskId) is TaskStatus.Finished
            }

            assertIs<TaskStatus.Finished.Failed>(harness.manager.getTaskStatus(taskId))
            assertTrue(failures.get() in 1..3)
        } finally {
            eventCollector.cancelAndJoin()
            eventScope.cancel()
            harness.close()
        }
    }

    @Test
    fun endToEndOfflineEnqueueBackgroundDrain(): Unit = runBlocking {
        val fixture = AdapterJvmFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        val harness = MeeseeksHarness()
        val storeName = uniqueStoreName("end-to-end")
        harness.coordinator.register(storeName, store, adapterJvmDrainPolicy())
        val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var watchJob: Job? = null
        try {
            store.mutate(AdapterJvmKey("key"), fixture.appendRef, "+background")
            watchJob = watchScope.launch { harness.coordinator.watch(storeName) }

            awaitCondition {
                fixture.backend.pushAttempts.get() >= 1
            }
            fixture.backend.offline = false

            awaitCondition {
                fixture.backend.receivedPushes.size == 1 && store.pendingWrites().isEmpty()
            }
            assertEquals(listOf("+background"), fixture.backend.receivedPushes)
        } finally {
            watchJob?.cancelAndJoin()
            watchScope.cancel()
            harness.close()
            store.close()
        }
    }
}

private class MeeseeksHarness(
    maxRetryCount: Int = 2,
) {
    private val context = AdapterJvmAppContext()
    internal val manager: BGTaskManager
    internal val scheduler: MeeseeksDrainScheduler
    internal val coordinator: MutationDrainCoordinator

    init {
        lateinit var managerReference: BGTaskManager
        scheduler = MeeseeksDrainScheduler { managerReference }
        managerReference =
            Meeseeks.initialize(context) {
                maxRetryCount(maxRetryCount)
                minBackoff(50.milliseconds)
                register<StoreDrainPayload> { appContext ->
                    StoreDrainWorker(appContext, scheduler)
                }
            }
        manager = managerReference
        coordinator = mutationDrainCoordinator(scheduler)
    }

    internal fun activeTasks(storeName: String) =
        manager.listTasks().filter { scheduled ->
            val payload = scheduled.task.payload as? StoreDrainPayload
            payload?.storeName == storeName &&
                (scheduled.status == TaskStatus.Pending || scheduled.status == TaskStatus.Running)
        }

    internal fun close() {
        manager.cancelAll()
        coordinator.close()
    }
}

private fun immediateRequest(storeName: String): DrainRequest =
    DrainRequest(
        storeName = storeName,
        constraints =
            DrainConstraints(
                requiresNetwork = false,
                requiresCharging = false,
            ),
        earliestDelay = Duration.ZERO,
    )

private fun uniqueStoreName(prefix: String): String =
    "$prefix-${UUID.randomUUID().toString().take(8)}"

private suspend fun awaitCondition(
    timeout: Duration = 30.seconds,
    poll: Duration = 50.milliseconds,
    condition: suspend () -> Boolean,
) {
    withTimeout(timeout) {
        while (!condition()) delay(poll)
    }
}

