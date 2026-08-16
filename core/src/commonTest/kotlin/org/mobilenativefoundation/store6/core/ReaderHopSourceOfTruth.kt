@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/**
 * Adversarial test decorator that adds a real-dispatcher hop and yield to widen the
 * queued-reader-frame race.
 *
 * SQLDelight's `readContext` switches before row capture, so its adversarial lane uses an
 * equivalent post-capture decorator instead of treating `Default` versus `EmptyCoroutineContext`
 * as this seam. This decorator is test-only and unpublished; mutations are left untouched.
 */
internal class ReaderHopSourceOfTruth<K : StoreKey, V : Any>(
    private val delegate: SourceOfTruth<K, V>,
) : SourceOfTruth<K, V> {
    override fun reader(key: K): Flow<V?> =
        delegate.reader(key)
            .map {
                yield()
                it
            }.flowOn(Dispatchers.Default)

    override suspend fun write(
        key: K,
        value: V,
    ) {
        delegate.write(key, value)
    }

    override suspend fun delete(key: K) {
        delegate.delete(key)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        delegate.deleteNamespace(namespace)
    }

    override suspend fun deleteAll() {
        delegate.deleteAll()
    }
}
