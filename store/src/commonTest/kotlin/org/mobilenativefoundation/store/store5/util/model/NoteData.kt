package org.mobilenativefoundation.store.store5.util.model

internal sealed class NoteData {
    data class Single(val item: Note) : NoteData()
    data class Collection(val items: List<Note>) : NoteData()
}

internal data class NotesWriteResponse(
    val key: String,
    val ok: Boolean
)

internal data class NetworkNote(
    val data: NoteData? = null
)

internal data class CommonNote(
    val data: NoteData? = null
)

internal data class SOTNote(
    val data: NoteData? = null
)

internal data class Note(
    val id: String,
    val title: String,
    val content: String
)
