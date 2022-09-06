package com.dropbox.market.notes.android.data.api

import com.dropbox.market.notes.android.data.MarketNote
import kotlinx.serialization.Serializable

@Serializable
data class NotesApiResponse(
    val value: List<MarketNote>
)