# Bookkeeper

```kotlin
interface Bookkeeper<Key : Any> {
    companion object {
        fun <Key : Any> by(
            getLastFailedSync: suspend (key: Key) -> Long?,
            setLastFailedSync: suspend (key: Key, timestamp: Long) -> Boolean,
            clear: suspend (key: Key) -> Boolean,
            clearAll: suspend () -> Boolean
        ): Bookkeeper<Key>
    }
}
```

## Example

```kotlin
fun provide(
    db: NotesDatabase,
    bookkeeping: BookkeepingDatabase
): Bookkeeper<NotesKey> = Bookkeeper.by(
    getLastFailedSync = { key: NotesKey ->
        require(key is NotesKey.Read)
        when (key) {
            is NotesKey.Read.ByNoteId -> {
                bookkeeping.getByNoteId(key.noteId)
            }
            is NotesKey.Read.ByAuthorId -> {
                val notes = db.getByAuthorId(key.authorId)
                bookkeeping.maxLastFailedSync(notes)
            }
            is NotesKey.Read.Paginated -> {
                val notes = db.getNotes(key.start, key.size)
                bookkeeping.maxLastFailedSync(notes)
            }
        }
    },
    setLastFailedSync = { key: NotesKey, timestamp: Long ->
        require(key !is NotesKey.Clear)
        try {
            when (key) {
                is NotesKey.Read.ByNoteId -> {
                    bookkeeping.upsert(key.noteId, timestamp)
                    true
                }
                is NotesKey.Read.ByAuthorId -> {
                    val notes = db.getByAuthorId(key.authorId)
                    notes.forEach { note: Note ->
                        bookkeeping.upsert(note.id, timestamp)
                    }
                    true
                }
                is NotesKey.Read.Paginated -> {
                    val notes = db.getNotes(key.start, key.size)
                    notes.forEach { note: Note ->
                        bookkeeping.upsert(note.id, timestamp)
                    }
                    true
                }
                is NotesKey.Write.ById -> {
                    bookkeeping.upsert(key.noteId, timestamp)
                    true
                }
                is NotesKey.Write.Create {
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
    },
    clear = { key: NotesKey ->
        require(key is NotesKey.Clear.ById)
        try {
            bookkeeping.deleteById(key.noteId)
        } catch (_: Throwable) {
            false
        }
    },
    clearAll = {
        require(key is NotesKey.Clear.All)
        try {
            bookkeeping.delete()
        } catch (_: Throwable) {
            false
        }
    },
)


private fun BookkeepingDatabase.maxLastFailedSync(notes: List<Note>): Long? {
    var maxLastFailedSync: Long? = null
    notes.forEach { note: Note ->
        val lastFailedSync = getByNoteId(note.id)
        if (maxLastFailedSync == null) {
            maxLastFailedSync = lastFailedSync
        } else if (lastFailedSync != null) {
            maxLastFailedSync = max(maxLastFailedSync, lastFailedSync)
        }
    }
    return maxLastFailedSync
}
```
