@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreKey

internal data class KeyIdentity(
    val namespace: String,
    val canonicalId: String,
)

internal fun StoreKey.identity(): KeyIdentity = KeyIdentity(namespace.value, canonicalId())

internal class JournalEntry<V : Any>(
    val mutationId: String,
    val mutatorId: String,
    val args: Any,
)

internal interface MutationJournal<V : Any> {
    suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<V>,
    ): String

    suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    )

    fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<V>>
}

internal class InMemoryMutationJournal<V : Any> : MutationJournal<V> {
    private val entries =
        MutableStateFlow<Map<KeyIdentity, List<JournalEntry<V>>>>(emptyMap())
    private val mutations = Mutex()

    override suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<V>,
    ): String =
        mutations.withLock {
            val current = entries.value
            entries.value = current + (key to current[key].orEmpty() + entry)
            entry.mutationId
        }

    override suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    ) {
        mutations.withLock {
            val current = entries.value
            val remaining =
                current[key]
                    .orEmpty()
                    .filterNot { it.mutationId == mutationId }
            entries.value =
                if (remaining.isEmpty()) {
                    current - key
                } else {
                    current + (key to remaining)
                }
        }
    }

    override fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<V>> =
        entries.value[key].orEmpty()
}
