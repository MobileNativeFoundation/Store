package org.mobilenativefoundation.store6.room

import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

internal actual fun inMemoryTestDatabaseBuilder(): RoomDatabase.Builder<Store6RoomTestDatabase> =
    Room.inMemoryDatabaseBuilder<Store6RoomTestDatabase>()

internal actual fun fileTestDatabaseBuilder(
    path: String,
): RoomDatabase.Builder<Store6RoomTestDatabase> =
    Room.databaseBuilder<Store6RoomTestDatabase>(name = path)

internal actual fun migrationV1DatabaseBuilder(
    path: String,
): RoomDatabase.Builder<MigrationV1Database> =
    Room.databaseBuilder<MigrationV1Database>(name = path)

internal actual fun migrationV2DatabaseBuilder(
    path: String,
): RoomDatabase.Builder<MigrationV2Database> =
    Room.databaseBuilder<MigrationV2Database>(name = path)

internal actual fun newTempDatabasePath(): String =
    File.createTempFile("store6-room-", ".db")
        .also { it.delete() }
        .absolutePath
