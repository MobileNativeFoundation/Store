# Room 3 (`room`)

Sidecar schema + DAO wiring. Spellings below match Store `main` @ `6790606d`.

Packages: `androidx.room3.*` (not `androidx.room`), `androidx.sqlite.SQLiteConnection`, `org.mobilenativefoundation.store6.room.*`. The builder is the top-level `store` function (`import org.mobilenativefoundation.store6.core.store`) — there is no `core.store` package.

## Dependencies / plugin

Replace `<version>` with the release you target. Nothing is published before `6.0.0-alpha01`. Do not invent coordinates.

```kotlin
plugins {
    id("androidx.room3") // not Room 2's `room`
    id("com.google.devtools.ksp")
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    sourceSets {
        getByName("main") // KMP: the source set that owns @Database (often commonMain)
            .languageSettings
            .optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")
    }
}

dependencies {
    implementation("org.mobilenativefoundation.store:core:<version>")
    implementation("org.mobilenativefoundation.store:room:<version>")
    ksp("androidx.room3:room3-compiler:3.0.0")
}
```

| Caveat | |
| --- | --- |
| Plugin | `androidx.room3`. Room 2's `room` / `room { }` is a different type identity. |
| Extension | `room3 { schemaDirectory(...) }` — not `room { }`. |
| Toolchain | Kotlin ≥ 2.3. Android: AGP ≥ 8.10. |
| Opt-in | Source-set `languageSettings.optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")` also covers generated DAO code. File-level `@OptIn` / `@file:OptIn` does not. |

## Database diff (v1 → v2)

Keep every user entity and DAO. Add two sidecar entities, one accessor, one version bump.

```kotlin
@Database(
    entities = [
        UserEntity::class,
        Store6BookkeepingEntity::class,
        Store6WatermarkEntity::class,
    ],
    version = 2,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun store6BookkeeperDao(): Store6BookkeeperDao
}
```

## Migration

Room 3 `Migration.migrate` is suspend; `Store6RoomSchema.createTables` is not (`androidx.sqlite` 2.7.0 `execSQL` is synchronous).

```kotlin
val addStore6Tables = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) =
        Store6RoomSchema.createTables(connection)
}

Room.databaseBuilder<AppDatabase>(name = path)
    .addMigrations(addStore6Tables)
```

## Schema

Sidecars only. User tables are untouched. Do not add `isStale` / `updatedAt` columns to user tables for Store metadata.

| Table | Owner |
| --- | --- |
| `store6_bookkeeping` | `Store6BookkeepingEntity` |
| `store6_watermarks` | `Store6WatermarkEntity` |
| user tables (e.g. `users`) | unchanged — no Store columns or constraints |

`createTables` runs two `CREATE TABLE IF NOT EXISTS` statements. No `ALTER TABLE` on user schemas.

## Wiring

`RoomBookkeeper(database: RoomDatabase, dao: Store6BookkeeperDao)`. Pass the same `RoomDatabase` to both adapters.

```kotlin
store<UserKey, User> {
    fetcher { key -> api.getUser(key.id) }
    persistence(
        RoomSourceOfTruth(
            database = database,
            rowReader = { key -> dao.user(key.id).map { row -> row?.toUser() } },
            rowWriter = { _, user -> dao.upsert(user.toEntity()) },
            rowDeleter = { key -> dao.delete(key.id) },
            namespaceDeleter = { _ -> dao.deleteAll() },
            allDeleter = { dao.deleteAll() },
        ),
    )
    bookkeeper(RoomBookkeeper(database, database.store6BookkeeperDao()))
}
```

| Adapter | Constructor |
| --- | --- |
| `RoomSourceOfTruth` | `(database, rowReader, rowWriter, rowDeleter, namespaceDeleter, allDeleter)` |
| `RoomBookkeeper` | `(database: RoomDatabase, dao: Store6BookkeeperDao)` |

`RoomPersister` does not exist.

## KMP placement

| App | Pattern |
| --- | --- |
| KMP | Common `@Database` + `@ConstructedBy` + `expect object : RoomDatabaseConstructor<T>` (`@Suppress("NO_ACTUAL_FOR_EXPECT")`; KSP writes the `actual`). Platform code builds the instance and injects it into common `store { }`. |
| JVM-only | Call `Room.databaseBuilder` in the same module. No `@ConstructedBy`. |

```kotlin
@Database(entities = [UserEntity::class, Store6BookkeepingEntity::class, Store6WatermarkEntity::class], version = 2)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun store6BookkeeperDao(): Store6BookkeeperDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

// platform actual — Room 3 KMP needs a driver; builder alone is not enough
Room.databaseBuilder<AppDatabase>(name = path)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Default)
    .build()
```

## Testing

Validate custom seams with `testing` (`SourceOfTruthContractKit`, `BookkeeperContractKit`) at the same `<version>`.
