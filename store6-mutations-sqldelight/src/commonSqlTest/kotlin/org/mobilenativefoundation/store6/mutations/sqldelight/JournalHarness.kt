@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage

internal class JournalHarness(
    val driver: SqlDriver,
) {
    val transacter: Transacter = JournalHarnessTransacter(driver)

    fun storage(): MutationJournalStorage =
        SqlDelightMutationJournalStorage(driver, transacter)

    fun tableNames(): Set<String> =
        queryList(
            """SELECT name FROM sqlite_master
               WHERE type = 'table'
                 AND (name LIKE 'store6_mutation_%' OR name LIKE 'store6_key_%')
               ORDER BY name""",
        ) { cursor -> cursor.getString(0)!! }.toSet()

    fun tableColumnSignatures(table: String): List<String> =
        queryList("PRAGMA table_info($table)") { cursor ->
            val name = cursor.getString(1)!!
            val type = cursor.getString(2)!!
            val notNull = cursor.getLong(3)!!
            val primaryKeyPosition = cursor.getLong(5)!!
            "$name:$type:$notNull:$primaryKeyPosition"
        }

    fun explicitIndexSignatures(table: String): Set<String> =
        queryList("PRAGMA index_list($table)") { cursor ->
            IndexHeader(
                name = cursor.getString(1)!!,
                unique = cursor.getLong(2)!!,
                partial = cursor.getLong(4)!!,
            )
        }.asSequence()
            .filterNot { header -> header.name.startsWith("sqlite_autoindex_") }
            .map { header ->
                val columns =
                    queryList("PRAGMA index_info(${header.name})") { cursor ->
                        cursor.getString(2)!!
                    }.joinToString(",")
                "${header.name}:unique=${header.unique}:partial=${header.partial}:$columns"
            }.toSet()

    fun storedText(
        table: String,
        column: String,
    ): Pair<String, String> =
        checkNotNull(
            queryOne("SELECT typeof($column), $column FROM $table LIMIT 1") { cursor ->
                cursor.getString(0)!! to cursor.getString(1)!!
            },
        )

    fun schemaVersion(): Long? =
        queryOne("SELECT version FROM store6_mutation_schema WHERE id = 0") { cursor ->
            cursor.getLong(0)!!
        }

    fun setSchemaVersion(version: Long) {
        execute("UPDATE store6_mutation_schema SET version = ? WHERE id = 0", 1) {
            bindLong(0, version)
        }
    }

    fun userVersion(): Long = queryOne("PRAGMA user_version") { it.getLong(0)!! }!!

    fun setUserVersion(version: Long) {
        execute("PRAGMA user_version = $version")
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
    ): T? =
        driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) mapper(cursor) else null) },
            0,
            {},
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

private class IndexHeader(
    val name: String,
    val unique: Long,
    val partial: Long,
)

private class JournalHarnessTransacter(driver: SqlDriver) : TransacterImpl(driver)

internal object JournalTestSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}

internal expect fun freshJournalHarness(): JournalHarness
