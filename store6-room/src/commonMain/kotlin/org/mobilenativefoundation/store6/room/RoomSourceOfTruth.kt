@file:OptIn(org.mobilenativefoundation.store6.core.DelicateStoreApi::class)

package org.mobilenativefoundation.store6.room

import androidx.room3.RoomDatabase
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.TransactionalSourceOfTruth
import kotlin.coroutines.CoroutineContext

private const val DATABASE_ADMISSION_STRIPE_COUNT: Int = 64
private const val CHILD_TRANSACTION_MUTATION_MESSAGE: String =
    "Room transaction mutations must remain sequential in the owning coroutine"

/** Bounded process-wide coordination. Hash collisions only over-serialize unrelated databases. */
private object RoomDatabaseAdmissionCoordinator {
    private val stripes: List<Mutex> =
        List(DATABASE_ADMISSION_STRIPE_COUNT) { Mutex() }

    fun stripeIndex(database: RoomDatabase): Int =
        (database.hashCode() and Int.MAX_VALUE) % DATABASE_ADMISSION_STRIPE_COUNT

    fun stripe(index: Int): Mutex = stripes[index]
}

/**
 * A coroutine-local frame for one held coordinator stripe.
 *
 * The frame deliberately stores no database. [ownerJob] lets a launched child fail before waiting
 * on its parent's stripe, while [parent] lets one coroutine nest operations on other stripes.
 */
private class RoomDatabaseAdmissionFrame(
    val stripeIndex: Int,
    var ownerJob: Job?,
    val token: Any,
    val parent: RoomDatabaseAdmissionFrame?,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<RoomDatabaseAdmissionFrame>

    override val key: CoroutineContext.Key<*> = Key
}

/** Type-erased delivery for one registered reader set in an outer Room transaction. */
private class PendingRoomEcho(
    val identity: Any,
    val generation: Long,
    val publishAt: suspend (Long) -> Unit,
    val settleAt: suspend (Long) -> Unit,
)

/**
 * Buffers mutation echoes until the outer Room transaction resolves.
 *
 * Per registration, `highestGeneration` covers every allocation while `finalPublisher` represents
 * only the last successful nested mutation. Commit publishes that final value once at the highest
 * generation; rollback settles the same high-water mark silently.
 */
private class RoomEchoTransactionFrame(
    val database: RoomDatabase,
    val admissionToken: Any,
    val parent: RoomEchoTransactionFrame?,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<RoomEchoTransactionFrame>

    override val key: CoroutineContext.Key<*> = Key

    private class Entry(
        var highestGeneration: Long,
        var settleAt: suspend (Long) -> Unit,
        var finalPublisher: (suspend (Long) -> Unit)? = null,
    )

    private val entries = LinkedHashMap<Any, Entry>()

    fun track(pending: List<PendingRoomEcho>) {
        pending.forEach { echo ->
            val entry = entries[echo.identity]
            if (entry == null) {
                entries[echo.identity] =
                    Entry(
                        highestGeneration = echo.generation,
                        settleAt = echo.settleAt,
                    )
            } else {
                entry.highestGeneration = echo.generation
                entry.settleAt = echo.settleAt
            }
        }
    }

    fun markSuccessful(pending: List<PendingRoomEcho>) {
        pending.forEach { echo ->
            entries.getValue(echo.identity).finalPublisher = echo.publishAt
        }
    }

    fun mergeCommitted(child: RoomEchoTransactionFrame) {
        child.entries.forEach { (identity, childEntry) ->
            val entry = entries[identity]
            if (entry == null) {
                entries[identity] =
                    Entry(
                        highestGeneration = childEntry.highestGeneration,
                        settleAt = childEntry.settleAt,
                        finalPublisher = childEntry.finalPublisher,
                    )
            } else {
                entry.highestGeneration = childEntry.highestGeneration
                entry.settleAt = childEntry.settleAt
                if (childEntry.finalPublisher != null) {
                    entry.finalPublisher = childEntry.finalPublisher
                }
            }
        }
    }

    fun mergeRolledBack(child: RoomEchoTransactionFrame) {
        child.entries.forEach { (identity, childEntry) ->
            val entry = entries[identity]
            if (entry == null) {
                entries[identity] =
                    Entry(
                        highestGeneration = childEntry.highestGeneration,
                        settleAt = childEntry.settleAt,
                    )
            } else {
                entry.highestGeneration = childEntry.highestGeneration
                entry.settleAt = childEntry.settleAt
            }
        }
    }

    suspend fun publishCommit() {
        entries.values.forEach { entry ->
            val publisher = entry.finalPublisher
            if (publisher == null) {
                entry.settleAt(entry.highestGeneration)
            } else {
                publisher(entry.highestGeneration)
            }
        }
    }

    suspend fun settleRollback() {
        entries.values.forEach { entry ->
            entry.settleAt(entry.highestGeneration)
        }
    }
}

/**
 * [TransactionalSourceOfTruth] over user DAO lambdas backed by one [RoomDatabase].
 *
 * Reader semantics on Room's table-granular InvalidationTracker use a generation-gated echo.
 * Every mutation through this instance allocates a monotonic per-key generation before its
 * transaction commits, then publishes either a generation-stamped echo after commit or a silent
 * settlement after rollback. Generations are never reused. Each collection captures a settlement
 * baseline when its echo subscription is established, always permits its first database snapshot,
 * drops later database re-emissions while an allocated generation is unconsumed, equality-
 * suppresses database signals otherwise, and emits every committed echo newer than its baseline.
 *
 * This makes equal-value rewrites through this instance re-emit exactly once and prevents writes
 * to another row in the same table from re-emitting an unchanged value. Equality is structural on
 * [V]; identity-equality values can therefore surface duplicate unchanged-row emissions after
 * same-table writes. Those duplicates are safe for the engine's conflation but noisier, so value
 * types are preferred. Echo publication uses a bounded suspending buffer: a starved collector
 * backpressures writers instead of dropping a mutation or leaving the generation gate open. Once
 * database admission is acquired, the root transaction and its commit-critical echo or rollback
 * settlement run under [NonCancellable]. Caller cancellation before admission remains cancellable;
 * after admission it cannot turn a durable commit into a boundary throw. A `CancellationException`
 * explicitly thrown by the mutation still rolls the transaction back and propagates.
 *
 * External changes through other database handles surface from the Room query while collected
 * and in every new collection's first emission. An external change racing a mutation through this
 * instance on the same key can be coalesced into that mutation's echo or the next re-query. One
 * theoretical stall window remains: a query can snapshot before a mutation, then delay delivery
 * until after that mutation's echo is consumed, surfacing one stale emission before the mutation's
 * own invalidation re-query self-heals it. If observed, the escalation is a per-key version stamp
 * written in the transaction and joined into the reader query.
 *
 * Top-level mutations and [withTransaction] acquire database-scoped mutation admission before a
 * Room writer. A bounded global hash stripe coordinates distinct adapter instances without
 * retaining databases. A coroutine-context frame makes same-Job, same-database nesting reentrant;
 * a launched child that tries to mutate on an inherited stripe fails immediately instead of
 * deadlocking its structured parent. Transaction blocks must therefore keep Room mutations
 * sequential in their owning coroutine. Queued top-level mutations wait without retaining a Room
 * writer.
 *
 * When [withTransaction] wraps nested writes for the future TD-11 mutations decorator, nested
 * mutations enlist in the outer transaction and remain invisible to readers until the outer
 * transaction commits. A rollback publishes no value. Multiple same-key writes in one outer
 * transaction coalesce to its final committed value, so readers never observe a transient nested
 * value that did not exist outside the transaction.
 *
 * Freeze candidate: issue 007 has landed; the seam freezes only after Matt signs the prepared
 * sign-off package.
 */
@ExperimentalStoreApi
public class RoomSourceOfTruth<K : StoreKey, V : Any>(
    private val database: RoomDatabase,
    private val rowReader: (K) -> Flow<V?>,
    private val rowWriter: suspend (K, V) -> Unit,
    private val rowDeleter: suspend (K) -> Unit,
    private val namespaceDeleter: suspend (StoreNamespace) -> Unit,
    private val allDeleter: suspend () -> Unit,
) : TransactionalSourceOfTruth<K, V> {
    private sealed interface Signal<out V> {
        /** Prepended at echo-subscription time; carries the generation floor for a collection. */
        class Baseline(
            val generation: Long,
        ) : Signal<Nothing>

        class FromMutation<V>(
            val generation: Long,
            val value: V?,
        ) : Signal<V>

        /** Advances reader-local generation state without publishing a value. */
        class Settled(
            val generation: Long,
        ) : Signal<Nothing>

        class FromDatabase<V>(
            val value: V?,
        ) : Signal<V>
    }

    private class Registration<V> {
        var activeReaders: Int = 0

        /**
         * Highest mutation generation allocated for this key. Written under database admission
         * before the transaction commits and never decremented; collectors read it through
         * StateFlow volatile semantics.
         */
        val allocatedGeneration: MutableStateFlow<Long> = MutableStateFlow(0L)

        /** Highest allocation resolved by either a committed echo or a silent rollback. */
        val settledGeneration: MutableStateFlow<Long> = MutableStateFlow(0L)

        /** Publishes mutation echoes; [Signal.Baseline] is prepended at subscription time. */
        val mutationEchoes: MutableSharedFlow<Signal<V>> =
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.SUSPEND,
            )
    }

    private val registrationsLock = Mutex()
    private val registrations = HashMap<Pair<String, String>, Registration<V>>()

    override fun reader(key: K): Flow<V?> =
        flow {
            val id = idOf(key)
            val registration = register(id)
            try {
                var emittedAny = false
                var lastEmitted: V? = null
                // -1 means the echo subscription has not established its baseline yet.
                var lastSeenGeneration = -1L
                merge(
                    registration.mutationEchoes.onSubscription {
                        emit(Signal.Baseline(registration.settledGeneration.value))
                    },
                    rowReader(key).map { value -> Signal.FromDatabase(value) },
                ).collect { signal ->
                    when (signal) {
                        is Signal.Baseline ->
                            if (signal.generation > lastSeenGeneration) {
                                lastSeenGeneration = signal.generation
                            }
                        is Signal.FromMutation ->
                            if (signal.generation > lastSeenGeneration) {
                                lastSeenGeneration = signal.generation
                                emittedAny = true
                                lastEmitted = signal.value
                                emit(signal.value)
                            }
                        is Signal.Settled ->
                            if (signal.generation > lastSeenGeneration) {
                                lastSeenGeneration = signal.generation
                            }
                        is Signal.FromDatabase -> {
                            val echoInFlight =
                                registration.allocatedGeneration.value > lastSeenGeneration
                            if (!emittedAny || (!echoInFlight && signal.value != lastEmitted)) {
                                emittedAny = true
                                lastEmitted = signal.value
                                emit(signal.value)
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { unregister(id) }
            }
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    listOfNotNull(registrations[idOf(key)])
                }
            },
            echoedValue = value,
        ) {
            rowWriter(key, value)
        }
    }

    override suspend fun delete(key: K) {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    listOfNotNull(registrations[idOf(key)])
                }
            },
            echoedValue = null,
        ) {
            rowDeleter(key)
        }
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    registrations
                        .filterKeys { id -> id.first == namespace.value }
                        .values
                        .toList()
                }
            },
            echoedValue = null,
        ) {
            namespaceDeleter(namespace)
        }
    }

    override suspend fun deleteAll() {
        mutateAndEcho(
            targets = {
                registrationsLock.withLock {
                    registrations.values.toList()
                }
            },
            echoedValue = null,
        ) {
            allDeleter()
        }
    }

    override suspend fun <R> withTransaction(block: suspend () -> R): R =
        withDatabaseAdmission { admissionToken ->
            val existing = transactionFrame(admissionToken)
            if (existing == null) {
                runRootTransaction(admissionToken) { block() }
            } else {
                runNestedTransaction(existing, admissionToken, block)
            }
        }

    /**
     * Acquires admission before a top-level writer, commits, releases that writer, and publishes
     * ordered echoes while retaining admission. Nested calls buffer against the outer transaction.
     */
    private suspend fun mutateAndEcho(
        targets: suspend () -> List<Registration<V>>,
        echoedValue: V?,
        block: suspend () -> Unit,
    ) {
        withDatabaseAdmission { admissionToken ->
            val existing = transactionFrame(admissionToken)
            if (existing == null) {
                runRootTransaction(admissionToken) {
                    mutateWithinTransaction(
                        transaction = checkNotNull(transactionFrame(admissionToken)),
                        admissionToken = admissionToken,
                        targets = targets,
                        echoedValue = echoedValue,
                        nested = false,
                        block = block,
                    )
                }
            } else {
                mutateWithinTransaction(
                    transaction = existing,
                    admissionToken = admissionToken,
                    targets = targets,
                    echoedValue = echoedValue,
                    nested = true,
                    block = block,
                )
            }
        }
    }

    /**
     * Allocates monotonic generations and attaches their final outcome to the outer transaction.
     */
    private suspend fun mutateWithinTransaction(
        transaction: RoomEchoTransactionFrame,
        admissionToken: Any,
        targets: suspend () -> List<Registration<V>>,
        echoedValue: V?,
        nested: Boolean,
        block: suspend () -> Unit,
    ) {
        val pending =
            targets().map { registration ->
                val generation = registration.allocatedGeneration.value + 1L
                registration.allocatedGeneration.value = generation
                PendingRoomEcho(
                    identity = registration,
                    generation = generation,
                    publishAt = { publishedGeneration ->
                        registration.settledGeneration.value = publishedGeneration
                        registration.mutationEchoes.emit(
                            Signal.FromMutation(publishedGeneration, echoedValue),
                        )
                    },
                    settleAt = { settledGeneration ->
                        registration.settledGeneration.value = settledGeneration
                        registration.mutationEchoes.emit(
                            Signal.Settled(settledGeneration),
                        )
                    },
                )
            }
        transaction.track(pending)

        if (nested) {
            runInTransaction(admissionToken, block)
        } else {
            block()
        }
        transaction.markSuccessful(pending)
    }

    /**
     * Runs one outer Room transaction, then resolves every buffered generation while admission is
     * still held. The admitted transaction and its resolution are cancellation-shielded.
     */
    private suspend fun <R> runRootTransaction(
        admissionToken: Any,
        block: suspend () -> R,
    ): R {
        val frame =
            RoomEchoTransactionFrame(
                database = database,
                admissionToken = admissionToken,
                parent = currentCoroutineContext()[RoomEchoTransactionFrame],
            )
        return withContext(NonCancellable + frame) {
            try {
                val result = runInTransaction(admissionToken, block)
                frame.publishCommit()
                result
            } catch (failure: Throwable) {
                try {
                    frame.settleRollback()
                } catch (settlementFailure: Throwable) {
                    failure.addSuppressed(settlementFailure)
                }
                throw failure
            }
        }
    }

    /**
     * Gives explicit nested transactions their own echo frame, mirroring Room's savepoint.
     */
    private suspend fun <R> runNestedTransaction(
        parent: RoomEchoTransactionFrame,
        admissionToken: Any,
        block: suspend () -> R,
    ): R {
        val child =
            RoomEchoTransactionFrame(
                database = database,
                admissionToken = admissionToken,
                parent = parent,
            )
        return try {
            val result =
                withContext(child) {
                    runInTransaction(admissionToken, block)
                }
            parent.mergeCommitted(child)
            result
        } catch (failure: Throwable) {
            parent.mergeRolledBack(child)
            throw failure
        }
    }

    /**
     * Acquires the database's bounded coordinator stripe before asking Room for a writer.
     *
     * Reentrancy is stripe-and-owner based so a same-Job hash collision cannot self-deadlock.
     * Transaction joining remains referentially database-scoped in [transactionFrame].
     */
    private suspend fun <R> withDatabaseAdmission(block: suspend (Any) -> R): R {
        val context = currentCoroutineContext()
        val ownerJob = context[Job]
        val stripeIndex = RoomDatabaseAdmissionCoordinator.stripeIndex(database)
        val inherited = context[RoomDatabaseAdmissionFrame]
        val held = inherited.findStripe(stripeIndex)
        if (held != null) {
            check(held.ownerJob === ownerJob) {
                CHILD_TRANSACTION_MUTATION_MESSAGE
            }
            return block(held.token)
        }

        return RoomDatabaseAdmissionCoordinator.stripe(stripeIndex).withLock {
            val frame =
                RoomDatabaseAdmissionFrame(
                    stripeIndex = stripeIndex,
                    ownerJob = currentCoroutineContext()[Job],
                    token = Any(),
                    parent = currentCoroutineContext()[RoomDatabaseAdmissionFrame],
                )
            withContext(NonCancellable + frame) {
                block(frame.token)
            }
        }
    }

    private fun RoomDatabaseAdmissionFrame?.findStripe(
        stripeIndex: Int,
    ): RoomDatabaseAdmissionFrame? {
        var candidate = this
        while (candidate != null) {
            if (candidate.stripeIndex == stripeIndex) {
                return candidate
            }
            candidate = candidate.parent
        }
        return null
    }

    private suspend fun <R> runInTransaction(
        admissionToken: Any,
        block: suspend () -> R,
    ): R =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val context = currentCoroutineContext()
                val frame = context[RoomDatabaseAdmissionFrame]
                checkNotNull(frame) { "Room mutation admission is missing" }
                val matchingFrame = frame.findByToken(admissionToken)
                checkNotNull(matchingFrame) { "Room mutation admission token is missing" }
                val ownerJob = context[Job]
                val previousOwner = matchingFrame.ownerJob
                matchingFrame.ownerJob = ownerJob
                try {
                    block()
                } finally {
                    matchingFrame.ownerJob = previousOwner
                }
            }
        }

    private fun RoomDatabaseAdmissionFrame.findByToken(
        admissionToken: Any,
    ): RoomDatabaseAdmissionFrame? {
        var candidate: RoomDatabaseAdmissionFrame? = this
        while (candidate != null) {
            if (candidate.token === admissionToken) {
                return candidate
            }
            candidate = candidate.parent
        }
        return null
    }

    private suspend fun transactionFrame(
        admissionToken: Any,
    ): RoomEchoTransactionFrame? {
        var candidate = currentCoroutineContext()[RoomEchoTransactionFrame]
        while (candidate != null) {
            if (candidate.database === database && candidate.admissionToken === admissionToken) {
                return candidate
            }
            candidate = candidate.parent
        }
        return null
    }

    private fun idOf(key: K): Pair<String, String> =
        key.namespace.value to key.canonicalId()

    private suspend fun register(id: Pair<String, String>): Registration<V> =
        registrationsLock.withLock {
            registrations
                .getOrPut(id) { Registration() }
                .also { registration -> registration.activeReaders += 1 }
        }

    private suspend fun unregister(id: Pair<String, String>) {
        registrationsLock.withLock {
            val registration = registrations[id] ?: return@withLock
            registration.activeReaders -= 1
            if (registration.activeReaders == 0) {
                registrations.remove(id)
            }
        }
    }
}
