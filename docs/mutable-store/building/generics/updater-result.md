# UpdaterResult

```kotlin
UpdaterResult: Any
```

## Example

```kotlin
sealed class NotesUpdaterResult {
    abstract val status: Int

    sealed class Success : NotesUpdaterResult() {
        data class Ok(override val status: Int) : Success()
        data class Created(override val status: Int) : Success()
    }

    sealed class Failure : NotesUpdaterResult() {
        data class ClientError(override val status: Int) : Failure()
        data class ServerError(override val status: Int) : Failure()
    }
}
```