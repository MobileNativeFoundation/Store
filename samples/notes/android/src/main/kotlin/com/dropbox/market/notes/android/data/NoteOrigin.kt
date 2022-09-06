package com.dropbox.market.notes.android.data

import kotlinx.serialization.Serializable

@Serializable
enum class NoteOrigin {
    Remote,
    LocalWrite,
    Store
}