package com.dropbox.market.samples.notes

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String,
    val title: String? = null,
    val content: String? = null
)