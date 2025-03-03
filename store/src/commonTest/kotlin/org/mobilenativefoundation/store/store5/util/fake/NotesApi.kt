package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.TestApi
import org.mobilenativefoundation.store.store5.util.model.InputNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.NotesWriteResponse

internal class NotesApi : TestApi<NotesKey, NetworkNote, InputNote, NotesWriteResponse> {
  internal val db = mutableMapOf<NotesKey, NetworkNote>()

  init {
    seed()
  }

  override fun get(key: NotesKey, fail: Boolean, ttl: Long?): NetworkNote {
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

  override fun post(key: NotesKey, value: InputNote, fail: Boolean): NotesWriteResponse {
    if (fail) {
      throw Exception()
    }

    db[key] = NetworkNote(value.data)

    return NotesWriteResponse(key, true)
  }

  private fun seed() {
    db[NotesKey.Single(Notes.One.id)] = NetworkNote(NoteData.Single(Notes.One))
    db[NotesKey.Single(Notes.Two.id)] = NetworkNote(NoteData.Single(Notes.Two))
    db[NotesKey.Single(Notes.Three.id)] = NetworkNote(NoteData.Single(Notes.Three))
    db[NotesKey.Single(Notes.Four.id)] = NetworkNote(NoteData.Single(Notes.Four))
    db[NotesKey.Single(Notes.Five.id)] = NetworkNote(NoteData.Single(Notes.Five))
    db[NotesKey.Single(Notes.Six.id)] = NetworkNote(NoteData.Single(Notes.Six))
    db[NotesKey.Single(Notes.Seven.id)] = NetworkNote(NoteData.Single(Notes.Seven))
    db[NotesKey.Single(Notes.Eight.id)] = NetworkNote(NoteData.Single(Notes.Eight))
    db[NotesKey.Single(Notes.Nine.id)] = NetworkNote(NoteData.Single(Notes.Nine))
    db[NotesKey.Single(Notes.Ten.id)] = NetworkNote(NoteData.Single(Notes.Ten))
    db[NotesKey.Collection(NoteCollections.Keys.OneAndTwo)] = NetworkNote(NoteCollections.OneAndTwo)
  }
}
