package org.mobilenativefoundation.store.store5.impl.extensions

import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.impl.RealConflictResolver
import org.mobilenativefoundation.store.store5.impl.RealMutableStore
import org.mobilenativefoundation.store.store5.impl.RealStore
import org.mobilenativefoundation.store.store5.impl.RealWriteRequestHandler
import org.mobilenativefoundation.store.store5.impl.RealWriteRequestStackHandler
import org.mobilenativefoundation.store.store5.impl.concurrent.RealThreadSafetyController

/**
 * An extension function for converting a [Store] into a [MutableStore].
 * @param updater An [Updater] instance responsible for updating the remote data source.
 * @param bookkeeper An optional [Bookkeeper] object that can track sync failures.
 * @return A [MutableStore] instance with the same [Key] and [Output] types as the original [Store].
 * @throws Exception when the store being converted was not created using the [StoreBuilder].
 */
@Suppress("UNCHECKED_CAST")
fun <Key : Any, Network : Any, Output : Any, Local : Any, Response : Any> Store<Key, Output>.asMutableStore(
    updater: Updater<Key, Output, Response>,
    bookkeeper: Bookkeeper<Key>?
): MutableStore<Key, Output> {
    val delegate = this as? RealStore<Key, Network, Output, Local>
        ?: throw Exception("MutableStore requires Store to be built using StoreBuilder")

    val threadSafetyController = RealThreadSafetyController<Key, Output>()

    val writeRequestStackHandler = RealWriteRequestStackHandler<Key, Output>(
        threadSafetyController = threadSafetyController
    )

    val writeRequestHandler = RealWriteRequestHandler<Key, Output>(
        delegate = delegate,
        updater = updater,
        writeRequestStackHandler = writeRequestStackHandler
    )

    val conflictResolver = RealConflictResolver<Key, Output, Response>(
        delegate = delegate,
        updater = updater,
        writeRequestStackHandler = writeRequestStackHandler,
        threadSafetyController = threadSafetyController,
        bookkeeper = bookkeeper
    )

    return RealMutableStore(
        delegate = delegate,
        writeRequestHandler = writeRequestHandler,
        conflictResolver = conflictResolver,
        threadSafetyController = threadSafetyController,
        writeRequestStackHandler = writeRequestStackHandler
    )
}
