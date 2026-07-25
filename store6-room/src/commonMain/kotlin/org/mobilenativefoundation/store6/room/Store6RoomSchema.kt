package org.mobilenativefoundation.store6.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Migration SQL for the adapter-owned TD-6 sidecar.
 *
 * When adding these tables to an existing Room database, bump its version by 1 and call
 * [createTables] from the migration.
 */
@ExperimentalStoreApi
public object Store6RoomSchema {
    public const val CREATE_BOOKKEEPING_TABLE: String =
        "CREATE TABLE IF NOT EXISTS `store6_bookkeeping` (" +
            "`namespace` TEXT NOT NULL, " +
            "`canonical_id` TEXT NOT NULL, " +
            "`written_at_epoch_millis` INTEGER, " +
            "`etag` TEXT, " +
            "`last_success_sequence` INTEGER, " +
            "`last_failure_at_epoch_millis` INTEGER, " +
            "`consecutive_failures` INTEGER NOT NULL, " +
            "`stale_sequence` INTEGER, " +
            "PRIMARY KEY(`namespace`, `canonical_id`))"

    public const val CREATE_WATERMARKS_TABLE: String =
        "CREATE TABLE IF NOT EXISTS `store6_watermarks` (" +
            "`scope` TEXT NOT NULL, " +
            "`sequence` INTEGER NOT NULL, " +
            "PRIMARY KEY(`scope`))"

    public fun createTables(connection: SQLiteConnection) {
        connection.execSQL(CREATE_BOOKKEEPING_TABLE)
        connection.execSQL(CREATE_WATERMARKS_TABLE)
    }
}
