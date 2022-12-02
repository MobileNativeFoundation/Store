package org.mobilenativefoundation.store.notes.app.extension

import com.squareup.sqldelight.runtime.coroutines.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import org.mobilenativefoundation.store.notes.android.app.NotesDatabase
import org.mobilenativefoundation.store.notes.android.app.WriterFailed
import org.mobilenativefoundation.store.notes.android.common.api.Note

fun NotesDatabase.tryGetNote(key: String): Flow<Note?> {
    return try {
        noteQueries.get(key).asFlow().mapNotNull { it.executeAsOne().convert() }
    } catch (_: Throwable) {
        flow {}
    }
}

fun NotesDatabase.tryWriteNote(key: String, input: Note): Boolean {
    return try {
        noteQueries.upsert(input.convert(key))
        true
    } catch (_: Throwable) {
        false
    }
}

fun NotesDatabase.tryDeleteNote(key: String): Boolean {
    return try {
        noteQueries.delete(key)
        true
    } catch (_: Throwable) {
        false
    }
}

fun NotesDatabase.tryDeleteAllNotes(): Boolean {
    return try {
        noteQueries.deleteAll()
        true
    } catch (_: Throwable) {
        false
    }
}

fun NotesDatabase.tryGetFailed(key: String): Long? {
    return try {
        writerFailedQueries.get(key).executeAsOneOrNull()?.datetime
    } catch (_: Throwable) {
        null
    }
}

fun NotesDatabase.tryWriteFailed(key: String, timestamp: Long): Boolean {
    return try {
        writerFailedQueries.upsert(WriterFailed(key, timestamp))
        true
    } catch (_: Throwable) {
        false
    }
}

fun NotesDatabase.tryDeleteFailed(key: String): Boolean {
    return try {
        writerFailedQueries.delete(key)
        true
    } catch (_: Throwable) {
        false
    }
}

fun NotesDatabase.tryDeleteAllFailed(): Boolean {
    return try {
        writerFailedQueries.deleteAll()
        true
    } catch (_: Throwable) {
        false
    }
}