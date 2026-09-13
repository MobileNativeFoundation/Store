package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.FreshnessEvidence
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.SourceAdoption
import org.mobilenativefoundation.store6.core.seam.StoreRuntime
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle

/** The engine-backed capability handle; obtained only through the seam `runtime()` accessor. */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class RealStoreRuntime<K : StoreKey, V : Any>(
    private val store: RealStore<K, V>,
) : StoreRuntime<K, V>, StoreWriteHandle<K, V> {
    override val writeHandle: StoreWriteHandle<K, V>
        get() = this

    override val keyEvents: Flow<KeyEvents>
        get() = store.events

    override val telemetry: StoreTelemetry?
        get() = store.telemetry

    override suspend fun apply(
        key: K,
        value: V,
    ) = store.withEngine(key) { engine -> engine.applyWrite(value) }

    override suspend fun captureFreshness(key: K): FreshnessEvidence =
        store.withEngine(key) { engine -> engine.captureFreshness() }

    override suspend fun applyAcknowledgement(
        key: K,
        value: V,
        etag: String?,
        freshnessEvidence: FreshnessEvidence?,
        adoption: SourceAdoption?,
    ) = store.withEngine(key) { engine ->
        engine.applyAcknowledgement(value, etag, freshnessEvidence, adoption)
    }

    /** Routes through KeyEngine.invalidate, producing Invalidated events and telemetry. */
    override suspend fun markStale(key: K) = store.invalidate(key)

    override suspend fun confirmFresh(
        key: K,
        etag: String?,
    ) = store.withEngine(key) { engine -> engine.confirmFresh(etag) }
}
