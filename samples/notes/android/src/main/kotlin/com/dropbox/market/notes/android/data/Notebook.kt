package com.dropbox.market.notes.android.data

import kotlinx.serialization.Serializable

@Serializable
sealed class Notebook {
    @Serializable
    data class Note(val value: MarketNote?) : Notebook()

    @Serializable
    data class Notes(val values: List<MarketNote>) : Notebook()
}