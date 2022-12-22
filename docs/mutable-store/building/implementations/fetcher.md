# Fetcher

```kotlin
interface Fetcher<Key : Any, Network : Any> {
    companion object {
        fun <Key : Any, Network : Any> of(
            fetch: suspend (key: Key) -> Network
        ): Fetcher<Key, Network>
    }
}
```

## Example

```kotlin
fun provide(
    api: NotesApi
): Fetcher<NotesKey, NetworkNote> = Fetcher.of { key: NotesKey ->
    require(key is NotesKey.Read)
    when (key) {
        is NotesKey.Read.ByNoteId -> api.getNoteById(key.noteId)
        is NotesKey.Read.ByAuthorId -> api.getNotesByAuthorId(key.authorId)
        is NotesKey.Read.Paginated -> api.getNotes(key.start, key.size)
    }
}
```
