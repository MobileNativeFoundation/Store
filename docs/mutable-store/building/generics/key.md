# Key

```kotlin
Key: Any
```

## Examples

```kotlin
Int
```

```kotlin
data class NotesKey(val noteId: Int)
```

```kotlin
sealed class NotesKey {
    sealed class Read : NotesKey() {
        data class ByNoteId(val noteId: Int) : Read()
        data class ByAuthorId(val authorId: Int) : Read()
        data class Paginated(val start: Int, val size: Int) : Read()
    }

    sealed class Write : NotesKey() {
        object Create : Write()
        data class ById(val noteId: Int) : Write()
    }

    sealed class Clear : NotesKey() {
        object All : Clear()
        data class ById(val noteId: Int) : Clear()
    }
}
```