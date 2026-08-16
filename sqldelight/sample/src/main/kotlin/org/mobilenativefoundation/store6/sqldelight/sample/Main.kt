@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight.sample

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.sqldelight.SqlDelightBookkeeper
import org.mobilenativefoundation.store6.sqldelight.SqlDelightSourceOfTruth
import org.mobilenativefoundation.store6.sqldelight.sample.db.SampleDatabase
import org.mobilenativefoundation.store6.sqldelight.sample.db.User

private class UserKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

private object FakeApi {
    suspend fun user(id: String): User = User(id, "User $id", "user$id@example.com")
}

private val fakeApi = FakeApi

public fun main(args: Array<String>): Unit =
    runBlocking {
        val databaseFile = File("build/sample.db")
        if ("--reset" in args) {
            check(!databaseFile.exists() || databaseFile.delete()) {
                "Could not reset ${databaseFile.absolutePath}"
            }
        }
        databaseFile.parentFile.mkdirs()

        val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
        if (!driver.hasUserSchema()) {
            SampleDatabase.Schema.create(driver).value
        }
        val db = SampleDatabase(driver)
        var fetches = 0

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

        val key = UserKey("42")
        val startupFrames =
            store.stream(key)
                .take(if (db.userQueries.selectById(key.id).executeAsOneOrNull() == null) 2 else 1)
                .toList()
                .toMutableList()
        if (startupFrames.size < 2) startupFrames += store.stream(key).first()
        startupFrames.forEach { frame -> println(frame.describe()) }
        val user = store.get(key)
        println("get: ${user.name} <${user.email}>")
        if (fetches == 0) {
            println("served from SQLDelight without a refetch (durable meta survived the restart): fetches=0")
        } else {
            println("fetched once and persisted row + durable meta atomically: fetches=$fetches")
        }

        // close() is synchronous and idempotent, cancels active collectors, and later operations
        // fail immediately with IllegalStateException("Store is closed."). The engine retains at
        // most maxIdleKeys (default 128) quiescent engines; this sample uses one key.
        store.close()
        store.close()
        check(runCatching { store.stream(key) }.exceptionOrNull()?.message == "Store is closed.")
        driver.close()
    }

private fun SqlDriver.hasUserSchema(): Boolean =
    executeQuery(
        identifier = null,
        sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'user'",
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 0,
    ).value

private fun StoreResult<User>.describe(): String =
    when (this) {
        is StoreResult.Loading -> "stream: Loading"
        is StoreResult.Data -> "stream: Data(name=${value.name}, origin=$origin)"
        is StoreResult.Revalidated -> "stream: Revalidated(age=$age)"
        is StoreResult.Error -> "stream: Error($error)"
    }
