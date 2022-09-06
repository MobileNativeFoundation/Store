package com.dropbox.market.notes.android.data

import kotlinx.serialization.Serializable

@Serializable
data class MarketNote(
    val id: String,
    val key: String,
    var title: String? = null,
    var content: String? = null,
    var origin: NoteOrigin = NoteOrigin.Remote
)