package org.mobilenativefoundation.store.store5.impl

/**
 * This internal interface defines a protocol for enabling conflict resolution.
 * @param Key the type of key used in [RealStore].
 */
internal interface ConflictResolver<Key : Any> {

    /**
     * Enables conflict resolution by updating the network data source with the latest value for the given key.
     * @param key The key for which conflicts need to be resolved.
     */
    suspend fun eagerlyResolveConflicts(key: Key)
}
