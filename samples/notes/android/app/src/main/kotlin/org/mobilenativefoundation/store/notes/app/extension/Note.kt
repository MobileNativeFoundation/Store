package org.mobilenativefoundation.store.notes.app.extension

import org.mobilenativefoundation.store.notes.android.common.api.Note
import org.mobilenativefoundation.store.notes.app.data.note.RealNote
import org.mobilenativefoundation.store.notes.android.app.Note as NoteSq

fun NoteSq.convert(): Note = RealNote(
    id = id,
    title = title ?: "",
    content = content ?: ""
)

fun Note.convert(key: String): NoteSq = NoteSq(
    id = id,
    title = title,
    content = content,
    key = key
)