package com.dropbox.external.store5.fake.model

import kotlinx.serialization.Serializable

@Serializable
internal data class Note(
    val id: String,
    var title: String,
    var content: String,
)