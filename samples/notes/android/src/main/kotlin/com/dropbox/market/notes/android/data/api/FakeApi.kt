package com.dropbox.market.notes.android.data.api

import com.dropbox.market.notes.android.Key
import com.dropbox.market.notes.android.Note
import com.dropbox.market.notes.android.data.Notebook
import com.dropbox.market.notes.android.data.MarketNote
import com.dropbox.market.notes.android.data.asMarketNote
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.collections.set

class FakeApi(private val client: HttpClient) : Api<Key, Notebook> {

    private val data = mutableMapOf<String, MarketNote>()

    init {
        FakeNotes.list().forEach { note: Note -> data[note.key!!] = note.asMarketNote() }
    }

    private suspend fun hasInternetConnection() = client.get(API_URL).status == HttpStatusCode.OK

    override suspend fun get(key: Key): Notebook? = when (hasInternetConnection()) {
        true -> when (key) {
            Key.All -> Notebook.Notes(data.values.toList())
            is Key.Single -> Notebook.Note(data[key.key])
        }

        false -> null
    }

    override suspend fun post(note: MarketNote): Boolean {
        return if (hasInternetConnection()) {
            data[note.key] = note
            true
        } else {
            false
        }
    }

    companion object {
        private const val API_URL = "https://jsonplaceholder.typicode.com/todos/1"
    }

    override suspend fun getStatus(): Int {
        return if (hasInternetConnection()) 200 else 500
    }
}