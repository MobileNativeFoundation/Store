package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.sync.Mutex
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.UpdaterResult

internal class MutableStoreKeyState<Key : Any, Output : Any> {
    val localMutex = Mutex()
    val remoteMutex = Mutex()
    val pending = ArrayDeque<PendingStoreWrite<Key, Output>>()
}

internal class PendingStoreWrite<Key : Any, Output : Any>(
    val request: StoreWriteRequest<Key, Output, *>,
) {
    var acknowledged: UpdaterResult.Success? = null
}

internal class MutableStoreSyncSnapshot<Key : Any, Output : Any>(
    val value: Output,
    val entries: List<PendingStoreWrite<Key, Output>>,
)

/** Called only with localMutex held; distinct admissions retain identity even for a reused request. */
internal fun <Key : Any, Output : Any> acknowledgeSnapshot(
    state: MutableStoreKeyState<Key, Output>,
    snapshot: MutableStoreSyncSnapshot<Key, Output>,
    result: UpdaterResult.Success,
): List<PendingStoreWrite<Key, Output>> {
    val completed = ArrayList<PendingStoreWrite<Key, Output>>(snapshot.entries.size)
    for (entry in snapshot.entries) {
        if (entry.acknowledged == null && state.pending.remove(entry)) {
            entry.acknowledged = result
            completed.add(entry)
        }
    }
    return completed
}
