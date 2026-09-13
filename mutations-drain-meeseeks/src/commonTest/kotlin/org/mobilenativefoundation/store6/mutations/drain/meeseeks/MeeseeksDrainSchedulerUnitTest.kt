@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.ScheduledTask
import dev.mattramotar.meeseeks.runtime.TaskId
import dev.mattramotar.meeseeks.runtime.TaskPayload
import dev.mattramotar.meeseeks.runtime.TaskRequest
import dev.mattramotar.meeseeks.runtime.TaskResult
import dev.mattramotar.meeseeks.runtime.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyIdentity
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints
import org.mobilenativefoundation.store6.mutations.drain.DrainPolicy
import org.mobilenativefoundation.store6.mutations.drain.DrainRequest
import org.mobilenativefoundation.store6.mutations.drain.mutationDrainCoordinator
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage

class MeeseeksDrainSchedulerUnitTest {
    @Test
    fun scheduleFreshCreatesTask() = runTest {
        val manager = ScriptedBGTaskManager()
        val scheduler = attachedScheduler(manager)

        scheduler.schedule(drainRequest())

        assertEquals(listOf("list", "schedule:1"), manager.calls)
        assertEquals(TaskId("1"), scheduler.trackedTaskIds["users"])
    }

    @Test
    fun schedulePendingReschedules() = runTest {
        val manager = ScriptedBGTaskManager()
        val scheduler = attachedScheduler(manager)
        scheduler.schedule(drainRequest())
        val id = TaskId("1")
        manager.setStatus(id, TaskStatus.Pending)
        manager.clearCalls()

        scheduler.schedule(drainRequest(delaySeconds = 9))

        assertEquals(listOf("status:1", "reschedule:1"), manager.calls)
        assertEquals(id, scheduler.trackedTaskIds["users"])
    }

    @Test
    fun scheduleRunningAdoptsWithoutReschedulingRunningId() = runTest {
        val manager = ScriptedBGTaskManager()
        val scheduler = attachedScheduler(manager)
        scheduler.schedule(drainRequest())
        val runningId = TaskId("1")
        manager.setStatus(runningId, TaskStatus.Running)
        manager.clearCalls()

        scheduler.schedule(drainRequest(delaySeconds = 9))

        assertEquals(listOf("status:1", "schedule:2"), manager.calls)
        assertTrue(manager.calls.none { it == "reschedule:1" })
        assertEquals(TaskId("2"), scheduler.trackedTaskIds["users"])

        val recoveredManager = ScriptedBGTaskManager()
        val recoveredRunningId = TaskId("recovered-running")
        recoveredManager.listedTasks =
            listOf(scheduledTask(recoveredRunningId, TaskStatus.Running, StoreDrainPayload("users")))
        val recoveredScheduler = attachedScheduler(recoveredManager)

        recoveredScheduler.schedule(drainRequest())

        assertEquals(listOf("list"), recoveredManager.calls)
        assertEquals(recoveredRunningId, recoveredScheduler.trackedTaskIds["users"])
    }

    @Test
    fun staleTerminalTrackedIdIsDropped() = runTest {
        val manager = ScriptedBGTaskManager()
        val scheduler = attachedScheduler(manager)
        scheduler.schedule(drainRequest())
        val staleId = TaskId("1")
        manager.setStatus(staleId, TaskStatus.Finished.Completed)
        manager.clearCalls()

        scheduler.schedule(drainRequest(delaySeconds = 9))

        assertEquals(listOf("status:1", "list", "schedule:2"), manager.calls)
        assertNotEquals(staleId, scheduler.trackedTaskIds["users"])
    }

    @Test
    fun recoveryScanAdoptsPendingIgnoresTerminalSkipsUndecodable() = runTest {
        val manager = ScriptedBGTaskManager()
        val pendingId = TaskId("pending")
        manager.listedTasks =
            listOf(
                scheduledTask(TaskId("foreign"), TaskStatus.Pending, ForeignPayload),
                scheduledTask(
                    TaskId("terminal"),
                    TaskStatus.Finished.Failed,
                    StoreDrainPayload("users"),
                ),
                scheduledTask(pendingId, TaskStatus.Pending, StoreDrainPayload("users")),
            )
        val scheduler = attachedScheduler(manager)

        scheduler.schedule(drainRequest())

        assertEquals(listOf("list", "reschedule:pending"), manager.calls)
        assertEquals(pendingId, scheduler.trackedTaskIds["users"])
    }

    @Test
    fun concurrentSchedulesConverge() = runTest {
        val manager = ScriptedBGTaskManager()
        val scheduler = attachedScheduler(manager)

        coroutineScope {
            listOf(
                async(Dispatchers.Default) { scheduler.schedule(drainRequest(delaySeconds = 1)) },
                async(Dispatchers.Default) { scheduler.schedule(drainRequest(delaySeconds = 2)) },
            ).awaitAll()
        }

        assertEquals(1, scheduler.trackedTaskIds.size)
        assertTrue(manager.calls.count { it.startsWith("schedule:") } <= 2)
    }

    @Test
    fun cancelOnlyCancelsPending() = runTest {
        val pendingManager = ScriptedBGTaskManager()
        val pendingScheduler = attachedScheduler(pendingManager)
        pendingScheduler.schedule(drainRequest())
        pendingManager.clearCalls()
        pendingScheduler.cancel("users")
        assertEquals(listOf("status:1", "cancel:1"), pendingManager.calls)
        assertTrue(pendingScheduler.trackedTaskIds.isEmpty())

        listOf(
            TaskStatus.Running,
            TaskStatus.Finished.Cancelled,
            TaskStatus.Finished.Completed,
            TaskStatus.Finished.Failed,
        ).forEach { status ->
            val manager = ScriptedBGTaskManager()
            val scheduler = attachedScheduler(manager)
            scheduler.schedule(drainRequest())
            manager.setStatus(TaskId("1"), status)
            manager.clearCalls()

            scheduler.cancel("users")

            assertEquals(listOf("status:1"), manager.calls)
            assertTrue(scheduler.trackedTaskIds.isEmpty())
        }
    }

    @Test
    fun workerMapsOutcomesPerTable() = runTest {
        val appContext = testAppContext()
        val runtimeContext = RuntimeContext()

        val clearedFixture = WorkerFixture()
        val clearedStore = clearedFixture.openStore()
        val clearedManager = ScriptedBGTaskManager()
        val clearedScheduler = MeeseeksDrainScheduler { clearedManager }
        val clearedCoordinator = mutationDrainCoordinator(clearedScheduler)
        clearedCoordinator.register("cleared", clearedStore, unconstrainedPolicy())
        clearedStore.mutate(WorkerKey("cleared"), clearedFixture.appendRef, "+done")
        try {
            assertEquals(
                TaskResult.Success,
                StoreDrainWorker(appContext, clearedScheduler).run(
                    StoreDrainPayload("cleared"),
                    runtimeContext,
                ),
            )
        } finally {
            clearedCoordinator.close()
            clearedStore.close()
        }

        val remainingFixture = WorkerFixture()
        remainingFixture.backend.offline = true
        val remainingStore = remainingFixture.openStore()
        val remainingManager = ScriptedBGTaskManager()
        val remainingScheduler = MeeseeksDrainScheduler { remainingManager }
        val remainingCoordinator = mutationDrainCoordinator(remainingScheduler)
        remainingCoordinator.register("remaining", remainingStore, unconstrainedPolicy())
        remainingStore.mutate(WorkerKey("remaining"), remainingFixture.appendRef, "+pending")
        try {
            assertEquals(
                TaskResult.Success,
                StoreDrainWorker(appContext, remainingScheduler).run(
                    StoreDrainPayload("remaining"),
                    runtimeContext,
                ),
            )
        } finally {
            remainingCoordinator.close()
            remainingStore.close()
        }

        val retryFixture = WorkerFixture()
        retryFixture.backend.offline = true
        val retryStore = retryFixture.openStore()
        val retryManager = ScriptedBGTaskManager()
        retryManager.throwOnReschedule = true
        val retryScheduler = MeeseeksDrainScheduler { retryManager }
        val retryCoordinator = mutationDrainCoordinator(retryScheduler)
        retryCoordinator.register("retry", retryStore, unconstrainedPolicy())
        retryStore.mutate(WorkerKey("retry"), retryFixture.appendRef, "+pending")
        try {
            assertEquals(
                TaskResult.Retry,
                StoreDrainWorker(appContext, retryScheduler).run(
                    StoreDrainPayload("retry"),
                    runtimeContext,
                ),
            )
        } finally {
            retryCoordinator.close()
            retryStore.close()
        }

        val unavailableManager = ScriptedBGTaskManager()
        val unavailableScheduler = MeeseeksDrainScheduler { unavailableManager }
        val unavailableCoordinator = mutationDrainCoordinator(unavailableScheduler)
        try {
            val unavailable =
                assertIs<TaskResult.Failure.Transient>(
                    StoreDrainWorker(appContext, unavailableScheduler).run(
                        StoreDrainPayload("unknown"),
                        runtimeContext,
                    ),
                )
            assertIs<IllegalStateException>(unavailable.error)
        } finally {
            unavailableCoordinator.close()
        }

        val cancelledFixture = WorkerFixture()
        val pushGate = CompletableDeferred<Unit>()
        cancelledFixture.backend.pushGate = pushGate
        val cancelledStore = cancelledFixture.openStore()
        val cancelledManager = ScriptedBGTaskManager()
        val cancelledScheduler = MeeseeksDrainScheduler { cancelledManager }
        val cancelledCoordinator = mutationDrainCoordinator(cancelledScheduler)
        cancelledCoordinator.register("cancelled", cancelledStore, unconstrainedPolicy())
        cancelledStore.mutate(WorkerKey("cancelled"), cancelledFixture.appendRef, "+pending")
        try {
            val workerRun =
                async {
                    StoreDrainWorker(appContext, cancelledScheduler).run(
                        StoreDrainPayload("cancelled"),
                        runtimeContext,
                    )
                }
            testScheduler.runCurrent()
            workerRun.cancel()

            assertFailsWith<CancellationException> { workerRun.await() }
        } finally {
            pushGate.complete(Unit)
            cancelledCoordinator.close()
            cancelledStore.close()
        }
    }
}

private class ScriptedBGTaskManager : BGTaskManager {
    private class State(
        val nextId: Int = 1,
        val calls: List<String> = emptyList(),
        val statuses: Map<TaskId, TaskStatus> = emptyMap(),
    )

    private val state = MutableStateFlow(State())

    var listedTasks: List<ScheduledTask> = emptyList()
    var throwOnReschedule: Boolean = false

    val calls: List<String>
        get() = state.value.calls

    override fun schedule(request: TaskRequest): TaskId {
        while (true) {
            val current = state.value
            val result = TaskId(current.nextId.toString())
            val updated =
                State(
                    nextId = current.nextId + 1,
                    calls = current.calls + "schedule:${result.value}",
                    statuses = current.statuses + (result to TaskStatus.Pending),
                )
            if (state.compareAndSet(current, updated)) return result
        }
    }

    override fun cancel(id: TaskId) {
        update { current ->
            State(
                nextId = current.nextId,
                calls = current.calls + "cancel:${id.value}",
                statuses = current.statuses + (id to TaskStatus.Finished.Cancelled),
            )
        }
    }

    override fun cancelAll() = Unit

    override fun reschedulePendingTasks() = Unit

    override fun getTaskStatus(id: TaskId): TaskStatus? {
        record("status:${id.value}")
        return state.value.statuses[id]
    }

    override fun listTasks(): List<ScheduledTask> {
        record("list")
        return listedTasks
    }

    override fun reschedule(
        id: TaskId,
        updatedRequest: TaskRequest,
    ): TaskId {
        record("reschedule:${id.value}")
        if (throwOnReschedule) error("scripted reschedule rejection")
        setStatus(id, TaskStatus.Pending)
        return id
    }

    override fun observeStatus(id: TaskId): Flow<TaskStatus?> =
        MutableStateFlow(state.value.statuses[id])

    fun setStatus(
        id: TaskId,
        status: TaskStatus,
    ) {
        update { current ->
            State(
                nextId = current.nextId,
                calls = current.calls,
                statuses = current.statuses + (id to status),
            )
        }
    }

    fun clearCalls() {
        update { current ->
            State(
                nextId = current.nextId,
                calls = emptyList(),
                statuses = current.statuses,
            )
        }
    }

    private fun record(call: String) {
        update { current ->
            State(
                nextId = current.nextId,
                calls = current.calls + call,
                statuses = current.statuses,
            )
        }
    }

    private inline fun update(transform: (State) -> State) {
        while (true) {
            val current = state.value
            if (state.compareAndSet(current, transform(current))) return
        }
    }
}

private object ForeignPayload : TaskPayload

private fun scheduledTask(
    id: TaskId,
    status: TaskStatus,
    payload: TaskPayload,
): ScheduledTask =
    ScheduledTask(
        id = id,
        status = status,
        task = TaskRequest(payload = payload),
        runAttemptCount = 0,
        createdAt = 0,
        updatedAt = 0,
    )

private fun attachedScheduler(manager: BGTaskManager): MeeseeksDrainScheduler {
    val scheduler = MeeseeksDrainScheduler { manager }
    mutationDrainCoordinator(scheduler)
    return scheduler
}

private fun drainRequest(delaySeconds: Int = 3): DrainRequest =
    DrainRequest(
        storeName = "users",
        constraints = DrainConstraints(requiresNetwork = false, requiresCharging = false),
        earliestDelay = delaySeconds.seconds,
    )

private fun unconstrainedPolicy(): DrainPolicy =
    DrainPolicy(
        constraints = DrainConstraints(requiresNetwork = false, requiresCharging = false),
    )

private class WorkerKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("worker")

    override fun canonicalId(): String = id
}

private object WorkerKeyResolver : MutationKeyResolver<WorkerKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): WorkerKey? =
        if (identity.namespace == "worker") WorkerKey(identity.canonicalId) else null
}

private object WorkerStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

private class WorkerBackend : MutationServer<WorkerKey, String> {
    var offline: Boolean = false
    var pushGate: CompletableDeferred<Unit>? = null

    override suspend fun push(
        request: MutationPush<WorkerKey, String>,
    ): MutationAck<WorkerKey, String> {
        pushGate?.await()
        check(!offline) { "backend is offline" }
        val value =
            when (val mine = request.mine) {
                is MutationPresence.Present -> mine.value
                MutationPresence.Absent -> error("Worker fixture expects a present mutation.")
            }
        return MutationPresentAck(
            authoritative = value,
            etag = null,
            canonicalKey = null,
        )
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

private class WorkerFixture {
    val backend = WorkerBackend()
    lateinit var appendRef: MutatorRef<WorkerKey, String, String>
        private set

    private val storage = InMemoryMutationJournalStorage()
    private val registry =
        mutatorRegistry<WorkerKey, String> {
            appendRef =
                mutator(
                    id = "worker-append",
                    version = 1,
                    codec = WorkerStringCodec,
                    stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
                ) { base, suffix ->
                    MutationPresence.Present(
                        ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                    )
                }
        }

    fun openStore(): MutationStore<WorkerKey, String> =
        mutationStore(
            registry = registry,
            server = backend,
            keyResolver = WorkerKeyResolver,
            valueCodecVersion = 1,
            valueCodec = WorkerStringCodec,
        ) {
            fetcher { "base" }
            journalStorage(storage)
        }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
