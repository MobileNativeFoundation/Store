package com.dropbox.store

typealias GetLastFailedWriteTime<Key> = suspend (key: Key) -> Long?
typealias SetLastFailedWriteTime<Key> = suspend (key: Key, datetime: Long?) -> Boolean
typealias DeleteFailedWriteRecord<Key> = suspend (key: Key) -> Boolean

class ConflictResolution<Key : Any, Input : Any, Output : Any> private constructor(
    val getLastFailedWriteTime: GetLastFailedWriteTime<Key>,
    val setLastFailedWriteTime: SetLastFailedWriteTime<Key>,
    val deleteFailedWriteRecord: DeleteFailedWriteRecord<Key>
) {
    class Builder<Key : Any, Input : Any, Output : Any> {
        private lateinit var getLastFailedWriteTime: GetLastFailedWriteTime<Key>
        private lateinit var setLastFailedWriteTime: SetLastFailedWriteTime<Key>
        private lateinit var deleteFailedWriteRecord: DeleteFailedWriteRecord<Key>

        fun getLastFailedWriteTime(get: GetLastFailedWriteTime<Key>) = apply { this.getLastFailedWriteTime = get }
        fun setLastFailedWriteTime(set: SetLastFailedWriteTime<Key>) = apply { this.setLastFailedWriteTime = set }
        fun deleteFailedWriteRecord(delete: DeleteFailedWriteRecord<Key>) =
            apply { this.deleteFailedWriteRecord = delete }

        fun build() = ConflictResolution<Key, Input, Output>(
            getLastFailedWriteTime,
            setLastFailedWriteTime,
            deleteFailedWriteRecord
        )
    }
}