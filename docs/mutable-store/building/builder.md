# Bringing It All Together

## There are two ways to build a `MutableStore`:

### A. Using a `StoreBuilder`

```kotlin
interface StoreBuilder<Key : Any, Network : Any, Common : Any, SOT : Any> {
    companion object {
        fun from<Key : Any, Network : Any, Common : Any, SOT : Any>(
            fetcher: Fetcher<Key, Network>,
            sourceOfTruth: SourceOfTruth<Key, SOT>
        ): StoreBuilder<Key, Network, Common, SOT>
    }
}
```

```kotlin
interface StoreBuilder<Key : Any, Network : Any, Common : Any, SOT : Any> {
    fun <UpdaterResult : Any> build(
        updater: Updater<Key, Common, UpdaterResult>,
        bookkeeper: Bookkeeper<Key>
    ): MutableStore<Key, Common>
}
```

#### Example

```kotlin
fun provide(
    fetcher: Fetcher<NotesKey, NetworkNote>,
    sourceOfTruth: SourceOfTruth<NotesKey, Note>,
    updater: Updater<NotesKey, CommonNote, NotesUpdaterResult>,
    bookkeeper: Bookkeeper<NotesKey>,
    converter: Converter<NetworkNote, CommonNote, Note>,
    validator: Validator<CommonNote>
): MutableStore<NotesKey, CommonNote> = StoreBuilder
    .from<NotesKey, NetworkNote, CommonNote, Note>(
        fetcher = fetcher,
        sourceOfTruth = sourceOfTruth
    )
    .converter(converter)
    .validator(validator)
    .build<NotesUpdaterResult>(
        updater = updater,
        bookkeeper = bookkeeper
    )
```

### B. Transforming a `Store`

```kotlin
fun <Key : Any, Network : Any, Common : Any, SOT : Any, UpdaterResult : Any> Store<Key, Common>.asMutableStore(
    updater: Updater<Key, Common, UpdaterResult>,
    bookkeeper: Bookkeeper<Key>
): MutableStore<Key, Common>
```

#### Example

```kotlin
fun provide(
    store: Store<NotesKey, CommonNote>,
    updater: Updater<NotesKey, CommonNote, NotesUpdaterResult>
    bookkeeper: Bookkeeper<NotesKey>
): MutableStore<NotesKey, CommonNote> =
    store.asMutableStore(
        updater = updater,
        bookkeeper = bookkeeper
    )
```