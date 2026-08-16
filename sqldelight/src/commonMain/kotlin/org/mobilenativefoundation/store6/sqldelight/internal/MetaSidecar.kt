@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight.internal

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.seam.KeyStatus

/**
 * Adapter-owned durable sidecar. Four tables are created and versioned by the adapter in
 * the user's database with zero user schema changes. SQLite `user_version` is never touched
 * because it belongs to the user's own schema. Present behavior is schema v1; the migration
 * ladder exists but has no steps yet.
 */
internal class MetaSidecar(
    private val driver: SqlDriver,
    transacter: Transacter,
) {
    init {
        transacter.transaction { ensureSchema() }
    }

    private fun ensureSchema() {
        executeAll(
            """CREATE TABLE IF NOT EXISTS store6_meta_schema(
               id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0),
               version INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS store6_meta_sequence(
               id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0),
               next_value INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS store6_meta(
               namespace TEXT NOT NULL,
               canonical_id TEXT NOT NULL,
               written_at_epoch_millis INTEGER,
               etag TEXT,
               success_sequence INTEGER,
               stale_sequence INTEGER,
               failure_at_epoch_millis INTEGER,
               consecutive_failures INTEGER NOT NULL DEFAULT 0,
               PRIMARY KEY (namespace, canonical_id))""",
            """CREATE TABLE IF NOT EXISTS store6_meta_watermark(
               scope TEXT NOT NULL PRIMARY KEY,
               sequence INTEGER NOT NULL)""",
            "INSERT OR IGNORE INTO store6_meta_schema(id, version) VALUES (0, 1)",
            "INSERT OR IGNORE INTO store6_meta_sequence(id, next_value) VALUES (0, 0)",
        )

        val version = queryLong("SELECT version FROM store6_meta_schema WHERE id = 0")
        check(version != null && version <= SCHEMA_VERSION) {
            "sqldelight found durable-meta schema version $version in this database, but " +
                "this adapter supports up to $SCHEMA_VERSION. Upgrade the sqldelight " +
                "dependency for this database, or restore the database."
        }
        // version < SCHEMA_VERSION: apply migration steps here when they exist (none at v1).
    }

    // SoT-facing operations. Multi-statement methods require a caller-owned transaction.

    fun stampWrite(namespace: String, canonicalId: String, nowEpochMillis: Long) {
        ensureMetaRow(namespace, canonicalId)
        execute(
            """UPDATE store6_meta
               SET written_at_epoch_millis = ?, etag = NULL
               WHERE namespace = ? AND canonical_id = ?""",
            3,
        ) {
            bindLong(0, nowEpochMillis)
            bindString(1, namespace)
            bindString(2, canonicalId)
        }
    }

    fun deleteRow(namespace: String, canonicalId: String) {
        execute("DELETE FROM store6_meta WHERE namespace = ? AND canonical_id = ?", 2) {
            bindString(0, namespace)
            bindString(1, canonicalId)
        }
    }

    fun deleteNamespaceRows(namespace: String) {
        execute("DELETE FROM store6_meta WHERE namespace = ?", 1) {
            bindString(0, namespace)
        }
    }

    fun deleteAllRows() {
        execute("DELETE FROM store6_meta")
    }

    // Bookkeeper-facing operations. Multi-statement methods require a caller-owned transaction.

    fun nextSequence(): Long {
        val current =
            queryLong("SELECT next_value FROM store6_meta_sequence WHERE id = 0") ?: 0L
        check(current < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
        execute("UPDATE store6_meta_sequence SET next_value = next_value + 1 WHERE id = 0")
        return current + 1L
    }

    fun recordSuccess(namespace: String, canonicalId: String, meta: StoreMeta) {
        val sequence = nextSequence()
        ensureMetaRow(namespace, canonicalId)
        execute(
            """UPDATE store6_meta
               SET written_at_epoch_millis = ?, etag = ?, success_sequence = ?,
                   failure_at_epoch_millis = NULL, consecutive_failures = 0
               WHERE namespace = ? AND canonical_id = ?""",
            5,
        ) {
            bindLong(0, meta.writtenAtEpochMillis)
            bindString(1, meta.etag)
            bindLong(2, sequence)
            bindString(3, namespace)
            bindString(4, canonicalId)
        }
    }

    fun recordFailure(namespace: String, canonicalId: String, atEpochMillis: Long) {
        ensureMetaRow(namespace, canonicalId)
        execute(
            """UPDATE store6_meta
               SET failure_at_epoch_millis = ?,
                   consecutive_failures = consecutive_failures + 1
               WHERE namespace = ? AND canonical_id = ?""",
            3,
        ) {
            bindLong(0, atEpochMillis)
            bindString(1, namespace)
            bindString(2, canonicalId)
        }
    }

    fun markStale(namespace: String, canonicalId: String) {
        val sequence = nextSequence()
        ensureMetaRow(namespace, canonicalId)
        execute(
            """UPDATE store6_meta SET stale_sequence = ?
               WHERE namespace = ? AND canonical_id = ?""",
            3,
        ) {
            bindLong(0, sequence)
            bindString(1, namespace)
            bindString(2, canonicalId)
        }
    }

    fun advanceNamespaceWatermark(namespace: String) {
        advanceWatermark("ns:$namespace")
    }

    fun advanceGlobalWatermark() {
        advanceWatermark(GLOBAL_SCOPE)
    }

    fun forget(namespace: String, canonicalId: String) {
        deleteRow(namespace, canonicalId)
    }

    fun forgetNamespace(namespace: String) {
        deleteNamespaceRows(namespace)
    }

    fun forgetAll() {
        deleteAllRows()
    }

    fun readStatus(namespace: String, canonicalId: String): KeyStatus? {
        val row = queryMetaRow(namespace, canonicalId)
        val coveringSequence =
            maxOf(
                row?.staleSequence ?: 0L,
                queryLong(
                    "SELECT sequence FROM store6_meta_watermark WHERE scope = ?",
                    1,
                ) { bindString(0, "ns:$namespace") } ?: 0L,
                queryLong(
                    "SELECT sequence FROM store6_meta_watermark WHERE scope = '$GLOBAL_SCOPE'",
                ) ?: 0L,
            )

        return when {
            row == null && coveringSequence == 0L -> null
            row == null ->
                KeyStatus(
                    meta = null,
                    lastSuccessSequence = null,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    durablyStale = true,
                )
            else ->
                KeyStatus(
                    meta =
                        row.writtenAtEpochMillis?.let { writtenAt ->
                            SidecarStoreMeta(writtenAt, row.etag)
                        },
                    lastSuccessSequence = row.successSequence,
                    lastFailureAtEpochMillis = row.failureAtEpochMillis,
                    consecutiveFailures = row.consecutiveFailures,
                    durablyStale = coveringSequence > (row.successSequence ?: 0L),
                )
        }
    }

    private fun advanceWatermark(scope: String) {
        val sequence = nextSequence()
        execute(
            "INSERT OR IGNORE INTO store6_meta_watermark(scope, sequence) VALUES (?, ?)",
            2,
        ) {
            bindString(0, scope)
            bindLong(1, sequence)
        }
        execute(
            "UPDATE store6_meta_watermark SET sequence = ? WHERE scope = ?",
            2,
        ) {
            bindLong(0, sequence)
            bindString(1, scope)
        }
    }

    private fun ensureMetaRow(namespace: String, canonicalId: String) {
        execute(
            """INSERT OR IGNORE INTO store6_meta(namespace, canonical_id)
               VALUES (?, ?)""",
            2,
        ) {
            bindString(0, namespace)
            bindString(1, canonicalId)
        }
    }

    private fun executeAll(vararg statements: String) {
        statements.forEach(::execute)
    }

    private fun execute(
        sql: String,
        parameters: Int = 0,
        binders: SqlPreparedStatement.() -> Unit = {},
    ) {
        driver.execute(null, sql, parameters, binders).value
    }

    private fun queryLong(
        sql: String,
        parameters: Int = 0,
        binders: SqlPreparedStatement.() -> Unit = {},
    ): Long? =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            },
            parameters,
            binders,
        ).value

    private fun queryMetaRow(namespace: String, canonicalId: String): MetaRow? =
        driver.executeQuery(
            null,
            """SELECT written_at_epoch_millis, etag, success_sequence, stale_sequence,
                      failure_at_epoch_millis, consecutive_failures
               FROM store6_meta WHERE namespace = ? AND canonical_id = ?""",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) cursor.toMetaRow() else null,
                )
            },
            2,
        ) {
            bindString(0, namespace)
            bindString(1, canonicalId)
        }.value

    private fun SqlCursor.toMetaRow(): MetaRow =
        MetaRow(
            writtenAtEpochMillis = getLong(0),
            etag = getString(1),
            successSequence = getLong(2),
            staleSequence = getLong(3),
            failureAtEpochMillis = getLong(4),
            consecutiveFailures = getLong(5)!!.toInt(),
        )

    private data class MetaRow(
        val writtenAtEpochMillis: Long?,
        val etag: String?,
        val successSequence: Long?,
        val staleSequence: Long?,
        val failureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
    )

    private class SidecarStoreMeta(
        override val writtenAtEpochMillis: Long,
        override val etag: String?,
    ) : StoreMeta

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val GLOBAL_SCOPE = "global"
    }
}
