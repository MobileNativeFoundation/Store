package com.dropbox.market.notes.android.data.api

import kotlinx.serialization.Serializable

@Serializable
data class NoteUpdate(
    val key: String,
    val title: String? = null,
    val content: String? = null
)