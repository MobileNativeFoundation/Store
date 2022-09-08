package com.dropbox.external.store5

typealias GetLastFailedWriteTime<Key> = suspend (key: Key) -> Long?
typealias SetLastFailedWriteTime<Key> = suspend (key: Key, datetime: Long?) -> Boolean
typealias DeleteFailedWriteRecord<Key> = suspend (key: Key) -> Boolean

/**
 * Resolves conflicts among local and remote data sources.
 */
data class ConflictResolver<Key : Any, Input : Any, Output : Any>(
    val getLastFailedWriteTime: GetLastFailedWriteTime<Key>,
    val setLastFailedWriteTime: SetLastFailedWriteTime<Key>,
    val deleteFailedWriteRecord: DeleteFailedWriteRecord<Key>
)