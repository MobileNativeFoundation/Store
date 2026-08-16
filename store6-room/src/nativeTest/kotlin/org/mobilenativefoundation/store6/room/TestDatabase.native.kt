package org.mobilenativefoundation.store6.room

import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.random.Random
import platform.posix.getenv

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

@OptIn(ExperimentalForeignApi::class)
internal actual fun newTempDatabasePath(): String {
    val temporaryDirectory = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
    val randomHex = Random.nextLong().toULong().toString(radix = 16)
    return "$temporaryDirectory/store6-room-$randomHex.db"
}
