# Source of Truth

```kotlin
interface SourceOfTruth<Key : Any, SOT : Any> {
    companion object {
        fun <Key : Any, SOT : Any> of(
            reader: (Key) -> Flow<SOT?>,
            writer: suspend (Key, SOT) -> Unit,
            delete: (suspend (Key) -> Unit)? = null,
            deleteAll: (suspend () -> Unit)? = null
        ): SourceOfTruth<Key, SOT>
    }
}
```

## Example

```kotlin
fun provide(
    db: NotesDatabase
): SourceOfTruth<NotesKey, Note> = SourceOfTruth.of(
    reader = { key: NotesKey ->
        require(key is NotesKey.Read)
        flow {
            when (key) {
                is NotesKey.Read.ByNoteId -> emit(db.getNoteById(key.noteId))
                is NotesKey.Read.ByAuthorId -> emit(db.getNotesByAuthorId(key.authorId))
                is NotesKey.Read.Paginated -> emit(db.getNotes(key.start, key.size))
            }
        }
    },
    writer = { key: NotesKey, input: SOT ->
        require(key is NotesKey.Write)
        when (key) {
            is NotesKey.Write.Create -> db.create(input)
            is NotesKey.Write.ById -> db.update(key.noteId, input)
        }
    },
    delete = { key: NotesKey ->
        require(key is NotesKey.Clear.ById)
        db.deleteById(key.noteId)
    },
    deleteAll = db.delete()
)
```
