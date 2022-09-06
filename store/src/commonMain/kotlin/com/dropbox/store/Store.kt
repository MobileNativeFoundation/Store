package com.dropbox.store

import kotlinx.coroutines.flow.Flow

typealias Read<Key, Output> = suspend (key: Key) -> Flow<Output?>
typealias Write<Key, Input> = suspend (key: Key, input: Input) -> Boolean
typealias Delete<Key> = suspend (key: Key) -> Boolean
typealias Clear = suspend () -> Boolean

class Store<Key, Input, Output> private constructor(
    val read: Read<Key, Output>,
    val write: Write<Key, Input>,
    val delete: Delete<Key>,
    val clear: Clear
) {
    class Builder<Key, Input, Output> {
        private lateinit var read: Read<Key, Output>
        private lateinit var write: Write<Key, Input>
        private lateinit var delete: Delete<Key>
        private lateinit var clear: Clear

        fun read(read: Read<Key, Output>) = apply { this.read = read }
        fun write(write: Write<Key, Input>) = apply { this.write = write }
        fun delete(delete: Delete<Key>) = apply { this.delete = delete }
        fun clear(clear: Clear) = apply { this.clear = clear }
        fun build() = Store<Key, Input, Output>(
            read = this.read,
            write = this.write,
            delete = this.delete,
            clear = this.clear
        )
    }
}