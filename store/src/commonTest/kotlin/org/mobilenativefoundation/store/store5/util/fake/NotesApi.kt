package org.mobilenativefoundation.store.store5.util.fake


import org.mobilenativefoundation.store.store5.util.TestApi
import org.mobilenativefoundation.store.store5.util.model.CommonNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.NotesWriteResponse

internal class NotesApi : TestApi<String, NetworkNote, CommonNote, NotesWriteResponse> {
    internal val db = mutableMapOf<String, NetworkNote>()

    init {
        seed()
    }

    override fun get(key: String, fail: Boolean, ttl: Long?): NetworkNote {
        if (fail) {
            throw Exception()
        }

        val networkNote = db[key]!!
        return if (ttl != null) {
            networkNote.copy(ttl = ttl)
        } else {
            networkNote
        }
    }

    override fun post(key: String, value: CommonNote, fail: Boolean): NotesWriteResponse {
        if (fail) {
            throw Exception()
        }

        db[key] = NetworkNote(value.data)

        return NotesWriteResponse(key, true)
    }

    private fun seed() {
        db[Notes.One.id] = NetworkNote(NoteData.Single(Notes.One))
        db[Notes.Two.id] = NetworkNote(NoteData.Single(Notes.Two))
        db[Notes.Three.id] = NetworkNote(NoteData.Single(Notes.Three))
        db[Notes.Four.id] = NetworkNote(NoteData.Single(Notes.Four))
        db[Notes.Five.id] = NetworkNote(NoteData.Single(Notes.Five))
        db[Notes.Six.id] = NetworkNote(NoteData.Single(Notes.Six))
        db[Notes.Seven.id] = NetworkNote(NoteData.Single(Notes.Seven))
        db[Notes.Eight.id] = NetworkNote(NoteData.Single(Notes.Eight))
        db[Notes.Nine.id] = NetworkNote(NoteData.Single(Notes.Nine))
        db[Notes.Ten.id] = NetworkNote(NoteData.Single(Notes.Ten))
    }
}