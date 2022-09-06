package com.dropbox.market.notes.android.data.api

import com.dropbox.market.notes.android.data.MarketNote

interface Api<Key : Any, Output: Any> {
    suspend fun get(key: Key): Output?
    suspend fun post(note: MarketNote): Boolean
    suspend fun getStatus(): Int
}