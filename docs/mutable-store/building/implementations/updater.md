# Updater

```kotlin
interface Updater<Key : Any, Common : Any, UpdaterResult : Any> {
    companion object {
        fun <Key : Any, Common : Any, UpdaterResult : Any> by(
            post: suspend (key: Key, input: Common) -> UpdaterResult,
            onCompletion: OnUpdaterCompletion<UpdaterResult>? = null,
        ): Updater<Key, Common, UpdaterResult>
    }
}

```

## Example

```kotlin
fun provide(
    api: NotesApi
): Updater<NotesKey, CommonNote, NotesUpdaterResult> =
    Updater.by(
        post = { key: NotesKey, input: CommonNote ->
            require(key is NotesKey.Write)
            when (key) {
                is NotesKey.Write.Create -> api.create(input)
                is NotesKey.Write.ById -> api.update(key.noteId, input)
            }
        },
        onCompletion = OnUpdaterCompletion(
            onSuccess = { success: UpdaterResult.Success ->
                UserLogger.post(StoreEvents.Update(success.status))
            },
            onFailure = { failure: UpdaterResult.Error ->
                UserLogger.post(StoreEvents.Update(failure.status))
            }
        )
    )
```
