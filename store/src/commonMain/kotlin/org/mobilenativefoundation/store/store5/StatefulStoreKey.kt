package org.mobilenativefoundation.store.store5

interface StatefulStoreKey<Key : Any> {
    val type: StatefulStoreType
    val value: Key

    fun asUnprocessed(): Unprocessed<Key>
    fun asProcessed(): Processed<Key>

    interface Unprocessed<Key : Any> : StatefulStoreKey<Key> {
        override val type: StatefulStoreType
            get() = StatefulStoreType.Unprocessed

        override fun asProcessed(): Processed<Key>
        override fun asUnprocessed(): Unprocessed<Key> {
            return this
        }
    }

    interface Processed<Key : Any> : StatefulStoreKey<Key> {
        override val type: StatefulStoreType
            get() = StatefulStoreType.Processed

        override fun asUnprocessed(): Unprocessed<Key>
        override fun asProcessed(): Processed<Key> {
            return this
        }
    }
}

enum class StatefulStoreType {
    Processed,
    Unprocessed
}
