package org.mobilenativefoundation.store.store5


interface StatefulStoreKey {

    fun unprocessed(): Unprocessed
    fun processed(): Processed
    interface Processed : StatefulStoreKey {
        override fun processed() = this
    }

    interface Unprocessed : StatefulStoreKey {
        override fun unprocessed() = this
    }
}