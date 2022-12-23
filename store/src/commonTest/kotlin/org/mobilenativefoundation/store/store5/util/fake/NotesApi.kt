package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.TestApi
import org.mobilenativefoundation.store.store5.util.model.CommonNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.Note
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.NotesWriteResponse

internal class NotesApi : TestApi<String, NetworkNote, CommonNote, NotesWriteResponse> {
    internal val db = mutableMapOf<String, NetworkNote>()

    init {
        seed()
    }

    override fun get(key: String, fail: Boolean): NetworkNote? {
        if (fail) {
            throw Exception()
        }

        return db[key]
    }

    override fun post(key: String, value: CommonNote, fail: Boolean): NotesWriteResponse {
        if (fail) {
            throw Exception()
        }

        db[key] = NetworkNote(value.data)

        return NotesWriteResponse(key, true)
    }

    private fun seed() {
        db["1-id"] = NetworkNote(NoteData.Single(Note("1-id", "1-title", "1-content")))
        db["2-id"] = NetworkNote(NoteData.Single(Note("2-id", "2-title", "2-content")))
        db["3-id"] = NetworkNote(NoteData.Single(Note("3-id", "3-title", "3-content")))
    }
}
