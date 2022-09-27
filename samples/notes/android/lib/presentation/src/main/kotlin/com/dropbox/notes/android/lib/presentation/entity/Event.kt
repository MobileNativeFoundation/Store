package com.dropbox.notes.android.lib.presentation.entity

import com.dropbox.notes.android.lib.presentation.navigation.Destination

interface Event {
    interface Navigation<out Id : Any> : Event {
        val destination: Destination<Id>
    }

    interface DataRequest<out K : Key, out In : Data> : Event {
        interface Read<out K : Key> : DataRequest<K, Nothing> {
            val key: K
        }

        interface Write<out K : Key, out In : Data> : DataRequest<K, In> {
            val key: K
            val input: In
        }
    }
}