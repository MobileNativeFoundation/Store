package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreRuntime
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.WallClock

/**
 * Store implementation backed by one supervised [KeyEngine] per canonical key.
 *
 * Each engine receives its own supervised child scope. Closing the store cancels the parent job
 * and all active engine work without allowing one key's fetch failure to cancel another key.
 * Freshness policies are honored per the [Freshness] contract; planning is delegated to the
 * engine's validator.
 */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class RealStore<K : StoreKey, V : Any>(
    fetcher: Fetcher<K, V>,
    private val sot: SourceOfTruth<K, V>,
    wallClock: WallClock,
    private val bookkeeper: Bookkeeper,
    validator: FreshnessValidator,
    internal val telemetry: StoreTelemetry?,
    private val overlay: Overlay<K, V>?,
    private val maxIdleKeys: Int,
) : Store<K, V> {
    private val storeJob = SupervisorJob()
    private val storeScope = CoroutineScope(Dispatchers.Default + storeJob)
    private val maintenanceCoordinator = MaintenanceCoordinator()
    internal val events =
        MutableSharedFlow<KeyEvents>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    internal val runtime: StoreRuntime<K, V> = RealStoreRuntime(this)
    private val registry =
        KeyRegistry<K, V>(maxIdleKeys) { key, id, hooks ->
            val engineJob = SupervisorJob(storeJob)
            KeyEngine(
                key = key,
                keyId = id,
                fetcher = fetcher,
                sot = sot,
                bookkeeper = bookkeeper,
                validator = validator,
                wallClock = wallClock,
                telemetry = telemetry,
                overlay = overlay,
                events = events,
                engineScope = CoroutineScope(storeScope.coroutineContext + engineJob),
                residencyHooks = hooks,
                maintenanceCoordinator = maintenanceCoordinator,
            )
        }

    init {
        storeJob.invokeOnCompletion { registry.clearOnClose() }
    }

    override fun stream(
        key: K,
        freshness: Freshness,
    ): Flow<StoreResult<V>> {
        ensureOpen()
        return flow {
            ensureOpen()
            registry.withEngine(key) { engine ->
                emitAll(engine.stream(freshness))
            }
        }
    }

    override suspend fun get(
        key: K,
        freshness: Freshness,
    ): V {
        return withEngine(key) { engine -> engine.get(freshness) }
    }

    override suspend fun invalidate(key: K) {
        ensureOpen()
        registry.withEngine(key) { engine -> engine.invalidate() }
    }

    override suspend fun invalidateNamespace(namespace: StoreNamespace) {
        ensureOpen()
        durably("invalidateNamespace", "namespace '${namespace.value}'") {
            bookkeeper.advanceStaleWatermark(namespace)
        }
        registry.forEachResident(namespace.value) { engine -> engine.invalidateResident() }
    }

    override suspend fun invalidateAll() {
        ensureOpen()
        durably("invalidateAll", "all namespaces") {
            bookkeeper.advanceGlobalStaleWatermark()
        }
        registry.forEachResident(namespace = null) { engine -> engine.invalidateResident() }
    }

    override suspend fun clear(key: K) {
        ensureOpen()
        registry.withEngine(key) { engine -> engine.clear() }
    }

    override suspend fun clearNamespace(namespace: StoreNamespace) {
        ensureOpen()
        val cleared =
            maintenanceCoordinator.withNamespaceMaintenance(namespace.value) {
                val firstSweep =
                    registry.snapshotAndForEachResident(namespace.value) { engine ->
                        engine.clearResident()
                    }
                durably("clearNamespace", "namespace '${namespace.value}'") {
                    sot.deleteNamespace(namespace)
                }
                durably("clearNamespace", "namespace '${namespace.value}'") {
                    bookkeeper.forgetNamespace(namespace)
                }
                registry.forEachResident(namespace.value) { engine -> engine.clearResident() }
                firstSweep
            }
        cleared.forEach { engine -> engine.notifyBulkClearCompleted() }
    }

    override suspend fun clearAll() {
        ensureOpen()
        val cleared =
            maintenanceCoordinator.withGlobalMaintenance {
                val firstSweep =
                    registry.snapshotAndForEachResident(namespace = null) { engine ->
                        engine.clearResident()
                    }
                durably("clearAll", "all namespaces") { sot.deleteAll() }
                durably("clearAll", "all namespaces") { bookkeeper.forgetAll() }
                registry.forEachResident(namespace = null) { engine -> engine.clearResident() }
                firstSweep
            }
        cleared.forEach { engine -> engine.notifyBulkClearCompleted() }
    }

    internal suspend fun <R> withEngine(
        key: K,
        action: suspend (KeyEngine<K, V>) -> R,
    ): R {
        ensureOpen()
        return registry.withEngine(key, action)
    }

    internal suspend fun residentEngineCountForTest(): Int = registry.residentCountForTest()

    internal suspend fun idleEngineCountForTest(): Int = registry.idleCountForTest()

    internal suspend fun createdEngineCountForTest(): Long = registry.createdCountForTest()

    internal suspend fun destroyedEngineCountForTest(): Long = registry.destroyedCountForTest()

    internal suspend fun awaitTerminationForTest() = storeJob.join()

    override fun close() {
        storeJob.cancel(CancellationException(STORE_CLOSED_MESSAGE))
        registry.clearOnClose()
    }

    /** Fails deterministically when an operation starts after [close]. */
    private fun ensureOpen() {
        if (!storeJob.isActive) {
            throw storeClosedException()
        }
    }

    /** Runs one durable maintenance step, preserving cancellation and typing other failures. */
    private suspend fun durably(
        operation: String,
        scope: String,
        step: suspend () -> Unit,
    ) {
        try {
            step()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val message =
                "$operation failed for $scope: ${failure.message}. Durable maintenance runs in " +
                    "a fixed order (stale mark before signal, delete before forget); completed " +
                    "steps remain applied and are conservative. Retry the operation and inspect " +
                    "the cause for the underlying persistence failure."
            throw StoreException(
                error = StoreError.Persistence(message = message, cause = failure),
                cause = failure,
            )
        }
    }
}
