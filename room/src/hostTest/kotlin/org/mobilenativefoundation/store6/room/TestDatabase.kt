@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "kit_rows",
    primaryKeys = ["namespace", "id"],
)
internal class KitRowEntity(
    val namespace: String,
    val id: String,
    val payload: String,
)

@Dao
internal interface KitRowDao {
    @Query("SELECT * FROM kit_rows WHERE namespace = :namespace AND id = :id")
    fun row(namespace: String, id: String): Flow<KitRowEntity?>

    @Upsert
    suspend fun upsert(row: KitRowEntity)

    @Query("DELETE FROM kit_rows WHERE namespace = :namespace AND id = :id")
    suspend fun delete(namespace: String, id: String)

    @Query("DELETE FROM kit_rows WHERE namespace = :namespace")
    suspend fun deleteNamespace(namespace: String)

    @Query("DELETE FROM kit_rows")
    suspend fun deleteAll()
}

@Database(
    entities = [
        KitRowEntity::class,
        Store6BookkeepingEntity::class,
        Store6WatermarkEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@ConstructedBy(Store6RoomTestDatabaseConstructor::class)
internal abstract class Store6RoomTestDatabase : RoomDatabase() {
    abstract fun kitRowDao(): KitRowDao

    abstract fun store6BookkeeperDao(): Store6BookkeeperDao
}

@Database(
    entities = [KitRowEntity::class],
    version = 1,
    exportSchema = false,
)
@ConstructedBy(MigrationV1DatabaseConstructor::class)
internal abstract class MigrationV1Database : RoomDatabase() {
    abstract fun kitRowDao(): KitRowDao
}

@Database(
    entities = [
        KitRowEntity::class,
        Store6BookkeepingEntity::class,
        Store6WatermarkEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@ConstructedBy(MigrationV2DatabaseConstructor::class)
internal abstract class MigrationV2Database : RoomDatabase() {
    abstract fun kitRowDao(): KitRowDao

    abstract fun store6BookkeeperDao(): Store6BookkeeperDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object Store6RoomTestDatabaseConstructor :
    RoomDatabaseConstructor<Store6RoomTestDatabase> {
    override fun initialize(): Store6RoomTestDatabase
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object MigrationV1DatabaseConstructor :
    RoomDatabaseConstructor<MigrationV1Database> {
    override fun initialize(): MigrationV1Database
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object MigrationV2DatabaseConstructor :
    RoomDatabaseConstructor<MigrationV2Database> {
    override fun initialize(): MigrationV2Database
}

internal expect fun inMemoryTestDatabaseBuilder(): RoomDatabase.Builder<Store6RoomTestDatabase>

internal expect fun fileTestDatabaseBuilder(
    path: String,
): RoomDatabase.Builder<Store6RoomTestDatabase>

internal expect fun migrationV1DatabaseBuilder(
    path: String,
): RoomDatabase.Builder<MigrationV1Database>

internal expect fun migrationV2DatabaseBuilder(
    path: String,
): RoomDatabase.Builder<MigrationV2Database>

internal expect fun newTempDatabasePath(): String

internal fun createTestDatabase(): Store6RoomTestDatabase =
    configureTestDatabase(inMemoryTestDatabaseBuilder())

internal fun openTestDatabase(path: String): Store6RoomTestDatabase =
    configureTestDatabase(fileTestDatabaseBuilder(path))

internal fun <T : RoomDatabase> configureTestDatabase(
    builder: RoomDatabase.Builder<T>,
): T =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
