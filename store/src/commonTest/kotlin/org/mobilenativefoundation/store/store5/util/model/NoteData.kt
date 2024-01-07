package org.mobilenativefoundation.store.store5.util.model

import org.mobilenativefoundation.store.store5.util.fake.NotesKey

internal sealed class NoteData {
    data class Single(val item: Note) : NoteData()
    data class Collection(val items: List<Note>) : NoteData()
}

internal data class NotesWriteResponse(
    val key: NotesKey,
    val ok: Boolean
)

internal data class NetworkNote(
    val data: NoteData? = null,
    val ttl: Long? = null,
)

internal data class InputNote(
    val data: NoteData? = null,
    val ttl: Long? = null,
)

internal data class OutputNote(
    val data: NoteData? = null,
    val ttl: Long
)

internal data class Note(
    val id: String,
    val title: String,
    val content: String
)
