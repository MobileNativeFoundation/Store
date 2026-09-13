package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema

internal class SqlHarness(
    val driver: SqlDriver,
) {
    private val harnessTransacter = HarnessTransacter(driver)

    val transacter: Transacter = harnessTransacter

    fun selectRow(namespace: String, id: String): Query<String> =
        BoundRowQuery(driver, namespace, id)

    fun upsertRow(namespace: String, id: String, value: String) {
        execute(
            """INSERT INTO test_rows(namespace, id, value) VALUES (?, ?, ?)
               ON CONFLICT(namespace, id) DO UPDATE SET value = excluded.value""",
            3,
        ) {
            bindString(0, namespace)
            bindString(1, id)
            bindString(2, value)
        }
        harnessTransacter.notifyRows()
    }

    fun deleteRow(namespace: String, id: String) {
        execute("DELETE FROM test_rows WHERE namespace = ? AND id = ?", 2) {
            bindString(0, namespace)
            bindString(1, id)
        }
        harnessTransacter.notifyRows()
    }

    fun deleteNamespace(namespace: String) {
        execute("DELETE FROM test_rows WHERE namespace = ?", 1) {
            bindString(0, namespace)
        }
        harnessTransacter.notifyRows()
    }

    fun deleteAll() {
        execute("DELETE FROM test_rows")
        harnessTransacter.notifyRows()
    }

    fun selectScratch(id: String): String? =
        queryOne("SELECT value FROM test_scratch WHERE id = ?", 1, { bindString(0, id) }) {
            it.getString(0)!!
        }

    fun upsertScratch(id: String, value: String) {
        execute(
            """INSERT INTO test_scratch(id, value) VALUES (?, ?)
               ON CONFLICT(id) DO UPDATE SET value = excluded.value""",
            2,
        ) {
            bindString(0, id)
            bindString(1, value)
        }
    }

    fun deleteScratch(id: String) {
        execute("DELETE FROM test_scratch WHERE id = ?", 1) { bindString(0, id) }
    }

    fun store6TableNames(): Set<String> =
        queryList(
            """SELECT name FROM sqlite_master
               WHERE type = 'table' AND name LIKE 'store6_meta%'
               ORDER BY name""",
        ) { it.getString(0)!! }.toSet()

    fun userVersion(): Long = queryOne("PRAGMA user_version") { it.getLong(0)!! }!!

    fun metaSchemaVersion(): Long? =
        queryOne("SELECT version FROM store6_meta_schema WHERE id = 0") { it.getLong(0)!! }

    fun setMetaSchemaVersion(version: Long) {
        execute("UPDATE store6_meta_schema SET version = ? WHERE id = 0", 1) {
            bindLong(0, version)
        }
    }

    fun metaSequence(): Long? =
        queryOne("SELECT next_value FROM store6_meta_sequence WHERE id = 0") { it.getLong(0)!! }

    fun setMetaSequence(value: Long) {
        execute("UPDATE store6_meta_sequence SET next_value = ? WHERE id = 0", 1) {
            bindLong(0, value)
        }
    }

    fun watermark(scope: String): Long? =
        queryOne(
            "SELECT sequence FROM store6_meta_watermark WHERE scope = ?",
            1,
            { bindString(0, scope) },
        ) { it.getLong(0)!! }

    fun metaRow(namespace: String, id: String): MetaRow? =
        queryOne(
            """SELECT written_at_epoch_millis, etag, success_sequence, stale_sequence,
                      failure_at_epoch_millis, consecutive_failures
               FROM store6_meta WHERE namespace = ? AND canonical_id = ?""",
            2,
            {
                bindString(0, namespace)
                bindString(1, id)
            },
        ) { cursor ->
            MetaRow(
                writtenAtEpochMillis = cursor.getLong(0),
                etag = cursor.getString(1),
                successSequence = cursor.getLong(2),
                staleSequence = cursor.getLong(3),
                failureAtEpochMillis = cursor.getLong(4),
                consecutiveFailures = cursor.getLong(5)!!.toInt(),
            )
        }

    fun executeRaw(sql: String) {
        execute(sql)
    }

    private fun execute(
        sql: String,
        parameters: Int = 0,
        binders: SqlPreparedStatement.() -> Unit = {},
    ) {
        driver.execute(null, sql, parameters, binders).value
    }

    private fun <T> queryOne(
        sql: String,
        mapper: (SqlCursor) -> T,
    ): T? = queryOne(sql, 0, {}, mapper)

    private fun <T> queryOne(
        sql: String,
        parameters: Int,
        binders: SqlPreparedStatement.() -> Unit,
        mapper: (SqlCursor) -> T,
    ): T? =
        driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) mapper(cursor) else null) },
            parameters,
            binders,
        ).value

    private fun <T> queryList(
        sql: String,
        mapper: (SqlCursor) -> T,
    ): List<T> =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val result = mutableListOf<T>()
                while (cursor.next().value) result += mapper(cursor)
                QueryResult.Value(result)
            },
            0,
            {},
        ).value
}

internal data class MetaRow(
    val writtenAtEpochMillis: Long?,
    val etag: String?,
    val successSequence: Long?,
    val staleSequence: Long?,
    val failureAtEpochMillis: Long?,
    val consecutiveFailures: Int,
)

private class HarnessTransacter(driver: SqlDriver) : TransacterImpl(driver) {
    fun notifyRows() {
        notifyQueries(-2) { emit -> emit("test_rows") }
    }
}

private class BoundRowQuery(
    private val driver: SqlDriver,
    private val namespace: String,
    private val id: String,
) : Query<String>({ cursor -> cursor.getString(0)!! }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(
            null,
            "SELECT value FROM test_rows WHERE namespace = ? AND id = ?",
            mapper,
            2,
        ) {
            bindString(0, namespace)
            bindString(1, id)
        }

    override fun addListener(listener: Listener) {
        driver.addListener("test_rows", listener = listener)
    }

    override fun removeListener(listener: Listener) {
        driver.removeListener("test_rows", listener = listener)
    }
}

internal object TestSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            null,
            """CREATE TABLE test_rows(
               namespace TEXT NOT NULL,
               id TEXT NOT NULL,
               value TEXT NOT NULL,
               PRIMARY KEY(namespace, id))""",
            0,
        ).value
        driver.execute(
            null,
            """CREATE TABLE test_scratch(
               id TEXT NOT NULL PRIMARY KEY,
               value TEXT NOT NULL)""",
            0,
        ).value
        return QueryResult.Value(Unit)
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}

internal expect fun freshHarness(): SqlHarness
