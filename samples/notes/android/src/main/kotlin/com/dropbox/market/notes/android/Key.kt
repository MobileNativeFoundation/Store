package com.dropbox.market.notes.android

import kotlinx.serialization.Serializable

@Serializable
sealed class Key {
    @Serializable
    data class Single(val key: String) : Key()

    @Serializable
    object All : Key()
}