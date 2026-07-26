# Store6 SQLDelight adapter

`store6-sqldelight` persists Store6 values and durable freshness metadata in one SQLDelight database. The adapter leaves your generated schema unchanged and creates its own four `store6_meta*` sidecar tables when it is constructed.

The Store6 seam remains **FREEZE CANDIDATE awaiting Matt's signature**.

## 15-minute existing-schema walkthrough

### 0. Prerequisites

Use Kotlin 2.3, SQLDelight 2.1.0, a JDK supported by your build, and a synchronous SQLDelight driver. This repository's executable sample uses JDK 11 bytecode and the JDBC SQLite driver. Until the snapshot is published remotely, publish `store6-core` and `store6-sqldelight` to Maven Local:

```shell
./gradlew :store6-core:publishToMavenLocal :store6-sqldelight:publishToMavenLocal
```

On Linux, native executables also require the SQLite development package and `pkg-config`; the module resolves SQLite's host library directory through `pkg-config` when linking its native-driver tests.

### 1. Add the adapter

Keep the SQLDelight plugin and the driver for your platform, then add the Store6 adapter:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.mobilenativefoundation.store:store6-sqldelight:6.0.0-SNAPSHOT")
    implementation("app.cash.sqldelight:sqlite-driver:2.1.0") // JVM sample
}
```

### 2. Keep your existing schema

No Store6 columns, queries, or migrations belong in your `.sq` files. The sample starts with this ordinary user table:

```sql
CREATE TABLE user (
  id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT NOT NULL
);

selectById:
SELECT * FROM user WHERE id = ?;

upsert:
INSERT OR REPLACE INTO user(id, name, email) VALUES (?, ?, ?);

deleteById:
DELETE FROM user WHERE id = ?;

deleteAll:
DELETE FROM user;
```

Create or migrate that schema as usual, then construct the adapter. It idempotently creates `store6_meta_schema`, `store6_meta_sequence`, `store6_meta`, and `store6_meta_watermark` itself. The executable sample probes `sqlite_master` and calls `SampleDatabase.Schema.create(driver)` only for a new database.

### 3. Wire generated queries to Store6

For this schema, `UserKey` implements `StoreKey`, uses `StoreNamespace("users")`, and returns its `id` from `canonicalId()`. `fakeApi` represents your network source and `fetches` is only a counter for the restart demonstration.

```kotlin
val sot = SqlDelightSourceOfTruth<UserKey, User>(
    driver = driver, transacter = db,
    readQuery = { key -> db.userQueries.selectById(key.id) { id, name, email -> User(id, name, email) } },
    writeRow = { _, user -> db.userQueries.upsert(user.id, user.name, user.email) },
    deleteRow = { key -> db.userQueries.deleteById(key.id) },
    deleteNamespaceRows = { ns -> if (ns.value == "users") db.userQueries.deleteAll() },
    deleteAllRows = { db.userQueries.deleteAll() },
)
val store = store<UserKey, User> {
    fetcher { key -> fakeApi.user(key.id).also { fetches++ } }
    persistence(sot)
    bookkeeper(SqlDelightBookkeeper(driver, db))
}
```

Three rules keep the boundary sound:

1. **Round trip:** after `writeRow(key, value)` returns, `readQuery(key)` must return the equivalent `value`.
2. **One driver:** `driver`, `transacter`, every generated query, every mutation callback, and `SqlDelightBookkeeper` must address the same database through the same `SqlDriver`. That is what makes each value-and-meta update atomic. Construct both adapters before exposing the driver to concurrent work because sidecar schema setup is synchronous; afterward, adapter reads and transactions sharing that driver are serialized.
3. **Synchronous transactions:** `withTransaction` is for same-database statements that complete without suspension and remain on the calling thread. Do network, delays, dispatcher changes, and other asynchronous work before or after it. A block that genuinely suspends throws `IllegalStateException`, cancels its child job, and rolls the transaction back; non-cooperative suspension is unsupported.

Adapter-owned writes and deletes notify matching active readers after commit, including equal-value rewrites. Reader signals are instance-scoped: direct SQL inside `withTransaction` and commits made through another adapter instance do not wake an already-active reader. A new collection still reads those external changes from the database.

Use one logical Store per database and namespace set. Instances sharing a database also share the sidecar's monotone sequence and watermarks.

### 4. Run twice

The included sample stores its database at `build/sample.db`. Reset it for the first process, then run the same binary again without resetting:

```shell
./gradlew :store6-sqldelight-sample:run --args=--reset
./gradlew :store6-sqldelight-sample:run
```

The first process fetches once and atomically persists the user plus durable metadata. The second process includes:

```text
served from SQLDelight without a refetch (durable meta survived the restart): fetches=0
```

Call `close()` when the Store is no longer needed. It is synchronous and idempotent, cancels active collectors, and makes every later operation fail immediately with `IllegalStateException("Store is closed.")`. A Store retains at most `maxIdleKeys` quiescent key engines (default 128); active collectors and in-flight work remain resident until they become quiescent. The sample uses one key.

## Drivers and current limitations

| Platform | SQLDelight dependency | Typical driver | Status |
| --- | --- | --- | --- |
| Android | `app.cash.sqldelight:android-driver:2.1.0` | `AndroidSqliteDriver` | Supported |
| Apple, Linux, Windows | `app.cash.sqldelight:native-driver:2.1.0` | native driver / `inMemoryDriver` | Supported |
| JVM desktop/server | `app.cash.sqldelight:sqlite-driver:2.1.0` | `JdbcSqliteDriver` | Supported |
| JS and Wasm | `app.cash.sqldelight:web-worker-driver:2.1.0` | web-worker driver | Not yet supported; this adapter currently requires synchronous drivers |

The published KMP artifact covers the canonical Store6 targets, but driver-backed execution is limited to targets for which SQLDelight provides a synchronous driver. JS and Wasm remain compile-only for this adapter today.

## Timing

The walkthrough was measured on July 22, 2026 from a nonexistent consumer directory at `/private/tmp/store6-sqldelight-consumer-20260722-t7`. The consumer referenced only Maven Local coordinates, not repository projects. Commands used `/usr/bin/time -p`; the edit interval used epoch seconds immediately before and after creating the clean Gradle files, unchanged `User.sq`, and wiring. Machine: macOS 26.2 arm64, OpenJDK 17.0.18, Gradle 8.11.1. The shared Gradle dependency cache was warm, while the consumer had no `.gradle`, `build`, or database state.

| Step | Measured wall time |
| --- | ---: |
| Publish `store6-core` and `store6-sqldelight` snapshots to Maven Local | 11.80 s |
| Create the clean consumer and add dependency, existing schema, and wiring | 40.00 s |
| First clean build and `run --args=--reset` | 9.96 s |
| Second process and durable-meta zero-refetch check | 0.96 s |
| **Total** | **62.72 s (1m 2.72s)** |

This automated run is evidence that the documented path fits comfortably inside 15 minutes on this machine; Matt should still spot-check the human walkthrough on his machine before merge.
