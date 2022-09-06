package com.dropbox.market.notes.android.data.api

import com.dropbox.market.notes.android.Key
import com.dropbox.market.notes.android.data.MarketNote
import com.dropbox.market.notes.android.data.Notebook
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

class NotesApi(private val client: HttpClient) : Api<Key, Notebook> {
    override suspend fun get(key: Key): Notebook? {
        return try {
            when (key) {
                Key.All -> {
                    val response = client.get("$API_URL/notes")
                    val serializer = Json { ignoreUnknownKeys = true }
                    val body = serializer.decodeFromJsonElement<NotesApiResponse>(response.body())
                    Notebook.Notes(body.value)
                }

                is Key.Single -> {
                    val response = client.get("$API_URL/notes/${key.key}")
                    val marketNote: MarketNote = response.body()
                    Notebook.Note(marketNote)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun post(note: MarketNote): Boolean {
        return try {
            val response = client.post("$API_URL/notes") {
                setBody(NoteUpdate(note.key, note.title, note.content))
                contentType(ContentType.Application.Json)
            }
            response.status == HttpStatusCode.OK
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun getStatus(): Int {
        return try {
            val response = client.get("$API_URL/status")
            response.status.value
        } catch (_: Throwable) {
            500
        }
    }

    companion object {
        private const val API_URL = "https://matt-ramotar-notes-api.herokuapp.com"
    }
}