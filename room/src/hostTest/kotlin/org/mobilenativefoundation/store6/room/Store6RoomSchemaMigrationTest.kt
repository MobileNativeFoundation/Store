@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

internal class Store6RoomSchemaMigrationTest {
    @Test
    fun manualMigration_matchesRoomExpectedSchema(): TestResult = runTest {
        val path = newTempDatabasePath()
        val key = RoomKitKey(StoreNamespace("migration"), "legacy")
        val legacyDatabase =
            configureTestDatabase(migrationV1DatabaseBuilder(path))
        try {
            legacyDatabase
                .kitRowDao()
                .upsert(KitRowEntity(key.namespace.value, key.canonicalId(), "v1"))
        } finally {
            legacyDatabase.close()
        }

        val migration =
            object : Migration(1, 2) {
                // Room 3's Migration.migrate is suspend; Store6RoomSchema.createTables stays
                // non-suspend because androidx.sqlite 2.7.0's execSQL is still synchronous.
                override suspend fun migrate(connection: SQLiteConnection) {
                    Store6RoomSchema.createTables(connection)
                }
            }
        val migratedDatabase =
            configureTestDatabase(
                migrationV2DatabaseBuilder(path).addMigrations(migration),
            )
        try {
            val legacyRow =
                migratedDatabase
                    .kitRowDao()
                    .row(key.namespace.value, key.canonicalId())
                    .first()
            assertEquals("v1", legacyRow?.payload)

            val bookkeeper =
                RoomBookkeeper(
                    migratedDatabase,
                    migratedDatabase.store6BookkeeperDao(),
                )
            bookkeeper.recordSuccess(
                key,
                TestStoreMeta(writtenAtEpochMillis = 1L, etag = "e1"),
            )

            val status = assertNotNull(bookkeeper.status(key))
            assertEquals(1L, status.meta?.writtenAtEpochMillis)
            assertEquals("e1", status.meta?.etag)
            assertEquals(
                "v1",
                migratedDatabase
                    .kitRowDao()
                    .row(key.namespace.value, key.canonicalId())
                    .first()
                    ?.payload,
            )
        } finally {
            migratedDatabase.close()
        }
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
