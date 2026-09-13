@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room.sample

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.room.RoomBookkeeper
import org.mobilenativefoundation.store6.room.RoomSourceOfTruth
import org.mobilenativefoundation.store6.room.Store6BookkeeperDao
import org.mobilenativefoundation.store6.room.Store6BookkeepingEntity
import org.mobilenativefoundation.store6.room.Store6RoomSchema
import org.mobilenativefoundation.store6.room.Store6WatermarkEntity

private val usersNamespace = StoreNamespace("users")

@Entity(tableName = "users")
internal data class UserEntity(
    @PrimaryKey
    val id: String,
    val name: String,
)

@Dao
internal interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    fun user(id: String): Flow<UserEntity?>

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM users")
    suspend fun deleteAll()
}

@Database(
    entities = [UserEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class LegacyDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
}

@Database(
    entities = [
        UserEntity::class,
        Store6BookkeepingEntity::class,
        Store6WatermarkEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    abstract fun store6BookkeeperDao(): Store6BookkeeperDao
}

internal data class User(
    val id: String,
    val name: String,
)

internal class UserKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = usersNamespace

    override fun canonicalId(): String = id
}

private class FakeApi {
    private val callsById = mutableMapOf<String, Int>()

    suspend fun getUser(id: String): User {
        val revision = callsById.getOrElse(id) { 0 } + 1
        callsById[id] = revision
        return User(id = id, name = "Fetched user $id, revision $revision")
    }

    fun callsFor(id: String): Int = callsById[id] ?: 0
}

private val addStore6Tables =
    object : Migration(1, 2) {
        // Room 3's Migration.migrate is suspend; Store6RoomSchema.createTables stays non-suspend
        // because androidx.sqlite 2.7.0's execSQL is still synchronous.
        override suspend fun migrate(connection: SQLiteConnection) =
            Store6RoomSchema.createTables(connection)
    }

public fun main(): Unit =
    runBlocking {
        val databaseFile =
            File.createTempFile("room-sample-", ".db").also { file ->
                check(file.delete()) { "Could not prepare ${file.absolutePath}" }
            }
        println("Room walkthrough database: ${databaseFile.absolutePath}")

        try {
            seedLegacyDatabase(databaseFile.absolutePath)

            val api = FakeApi()
            val legacyKey = UserKey("legacy")
            val remoteKey = UserKey("remote")

            println("\n1. Migrate the existing Room database and add Store")
            openAppDatabase(databaseFile.absolutePath).let { database ->
                val store = createStore(database, api)
                try {
                    val legacy =
                        store
                            .stream(legacyKey, Freshness.LocalOnly)
                            .first { frame -> frame is StoreResult.Data }
                            .requireData()
                    check(legacy.origin == Origin.SOT)
                    check(api.callsFor(legacyKey.id) == 0)
                    println(
                        "legacy row: Data(name=${legacy.value.name}, " +
                            "origin=${legacy.origin}), fetches=0",
                    )

                    val coldFrames = store.stream(remoteKey).take(2).toList()
                    check(coldFrames.first() is StoreResult.Loading)
                    val fetched = coldFrames.last().requireData()
                    check(fetched.origin == Origin.FETCHER)
                    check(api.callsFor(remoteKey.id) == 1)
                    coldFrames.forEach { frame -> println("cold row: ${frame.describe()}") }

                    store.close()
                    store.close()
                    val closedCallFailure =
                        runCatching { store.get(legacyKey) }.exceptionOrNull()
                    check(
                        closedCallFailure is IllegalStateException &&
                            closedCallFailure.message == "Store is closed.",
                    )
                    println(
                        "close: synchronous and idempotent; a later call failed immediately",
                    )
                } finally {
                    store.close()
                    database.close()
                }
            }

            println("\n2. Rebuild Store and Room over the same file")
            openAppDatabase(databaseFile.absolutePath).let { database ->
                val store = createStore(database, api)
                try {
                    val fetchesBeforeRestart = api.callsFor(remoteKey.id)
                    val restarted =
                        store
                            .stream(remoteKey)
                            .first { frame -> frame is StoreResult.Data }
                            .requireData()
                    check(restarted.origin == Origin.SOT)
                    check(!restarted.isStale)
                    check(!restarted.refreshing)
                    check(api.callsFor(remoteKey.id) == fetchesBeforeRestart)
                    println(
                        "restart: Data(name=${restarted.value.name}, " +
                            "origin=${restarted.origin}), fetches unchanged",
                    )

                    store.invalidateNamespace(usersNamespace)
                    println("persisted namespace invalidation")
                } finally {
                    store.close()
                    database.close()
                }
            }

            println("\n3. Rebuild again after invalidation")
            openAppDatabase(databaseFile.absolutePath).let { database ->
                val store = createStore(database, api)
                try {
                    val fetchesBeforeRefresh = api.callsFor(remoteKey.id)
                    val refreshed =
                        store
                            .stream(remoteKey)
                            .onEach { frame ->
                                println("invalidated restart: ${frame.describe()}")
                            }
                            .first { frame ->
                                frame is StoreResult.Data && frame.origin == Origin.FETCHER
                            }
                            .requireData()
                    check(api.callsFor(remoteKey.id) == fetchesBeforeRefresh + 1)
                    println("refetched: ${refreshed.value.name}")
                } finally {
                    store.close()
                    database.close()
                }
            }

            println(
                "\nDone. Durability lived in the Room row and Store6 sidecar, " +
                    "not in a retained Store engine.",
            )
            println(
                "Quiescent engine residency is bounded at 128 by default. " +
                    "Calls after Store.close() fail immediately.",
            )
        } finally {
            deleteDatabaseFiles(databaseFile.absolutePath)
        }
    }

private suspend fun seedLegacyDatabase(path: String) {
    val database = openLegacyDatabase(path)
    try {
        database.userDao().upsert(UserEntity(id = "legacy", name = "Legacy user"))
        println("seeded version-1 users table before Store existed")
    } finally {
        database.close()
    }
}

private fun openLegacyDatabase(path: String): LegacyDatabase =
    configureDatabase(Room.databaseBuilder<LegacyDatabase>(name = path))

private fun openAppDatabase(path: String): AppDatabase =
    configureDatabase(
        Room
            .databaseBuilder<AppDatabase>(name = path)
            .addMigrations(addStore6Tables),
    )

private fun <T : RoomDatabase> configureDatabase(
    builder: RoomDatabase.Builder<T>,
): T =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

private fun createStore(
    database: AppDatabase,
    api: FakeApi,
): Store<UserKey, User> {
    val dao = database.userDao()
    return store {
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
}

private fun UserEntity.toUser(): User = User(id = id, name = name)

private fun User.toEntity(): UserEntity = UserEntity(id = id, name = name)

private fun StoreResult<User>.requireData(): StoreResult.Data<User> =
    when (this) {
        is StoreResult.Data -> this
        else -> error("Expected Data, received ${describe()}")
    }

private fun StoreResult<User>.describe(): String =
    when (this) {
        is StoreResult.Loading -> "Loading"
        is StoreResult.Data ->
            "Data(name=${value.name}, origin=$origin, stale=$isStale, refreshing=$refreshing)"
        is StoreResult.Revalidated -> "Revalidated(age=$age)"
        is StoreResult.Error -> "Error(error=$error, servedStale=$servedStale)"
    }

private fun deleteDatabaseFiles(path: String) {
    listOf(path, "$path-wal", "$path-shm", "$path-journal").forEach { candidate ->
        File(candidate).delete()
    }
}
