# store6-sqldelight

Every spelling below is verified against Store `main` @ `6790606d`. Package `org.mobilenativefoundation.store6.sqldelight`. Both adapters are `@ExperimentalStoreApi` — callers need `@OptIn(ExperimentalStoreApi::class)`.

## Construction

| Adapter | Constructor |
| --- | --- |
| `SqlDelightSourceOfTruth<K : StoreKey, V : Any>` | `(driver, transacter, readQuery, writeRow, deleteRow, deleteNamespaceRows, deleteAllRows)` |
| `SqlDelightBookkeeper` | `(driver: SqlDriver, transacter: Transacter)` |

Optional on `SqlDelightSourceOfTruth` only: `wallClock: WallClock? = null`, `readContext: CoroutineContext = Dispatchers.Default`. Do not require them.

Call-site may pass the generated database as the transacter: `SqlDelightBookkeeper(driver, db)`.

```kotlin
val sot = SqlDelightSourceOfTruth<UserKey, User>(
    driver = driver,
    transacter = db,
    readQuery = { key -> db.userQueries.selectById(key.id) { id, name, email -> User(id, name, email) } },
    writeRow = { _, user -> db.userQueries.upsert(user.id, user.name, user.email) },
    deleteRow = { key -> db.userQueries.deleteById(key.id) },
    deleteNamespaceRows = { ns -> if (ns.value == "users") db.userQueries.deleteAll() },
    deleteAllRows = { db.userQueries.deleteAll() },
)
val store = store<UserKey, User> {
    fetcher { key -> fakeApi.user(key.id) }
    persistence(sot)
    bookkeeper(SqlDelightBookkeeper(driver, db))
}
```

## Sidecar tables

Adapter creates four tables. No `.sq` changes. `user_version` is never touched.

| Table |
| --- |
| `store6_meta_schema` |
| `store6_meta_sequence` |
| `store6_meta` |
| `store6_meta_watermark` |

## Boundary rules

| Rule | Law |
| --- | --- |
| Round trip | After `writeRow(key, value)` returns, `readQuery(key)` must return the equivalent `value`. |
| One `SqlDriver` | `driver`, `transacter`, every generated query, every mutation callback, and `SqlDelightBookkeeper` must use the same `SqlDriver`. |
| `withTransaction` is synchronous | A block that genuinely suspends throws `IllegalStateException`, cancels its child job, and rolls the transaction back. |

## Instances

Use one logical Store per database and namespace set. Instances sharing a database also share the sidecar's monotone sequence and watermarks.
