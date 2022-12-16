package org.mobilenativefoundation.store.store5.market.data.model

import kotlinx.serialization.Serializable

@Serializable
internal data class Note(
    val id: String,
    var title: String,
    var content: String,
)
