@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import org.mobilenativefoundation.store6.testing.BookkeeperFaultFixture
import org.mobilenativefoundation.store6.testing.BookkeeperStatusFaultFixture
import org.mobilenativefoundation.store6.testing.MutationFaultInjector
import org.mobilenativefoundation.store6.testing.MutationFaultPoint
import org.mobilenativefoundation.store6.testing.SourceOfTruthFaultFixture

internal fun sqlContractSourceFaultFixture(): SourceOfTruthFaultFixture<SqlTestKey, String> {
    val harness = freshHarness()
    val faults = MutationFaultInjector()
    val transacter = TransactionBoundaryFaults(harness.transacter, faults::reachTransactionBoundary)
    val source = SqlDelightSourceOfTruth<SqlTestKey, String>(
        driver = harness.driver,
        transacter = transacter,
        readQuery = { harness.selectRow(it.ns, it.id) },
        writeRow = { key, value -> harness.upsertRow(key.ns, key.id, value) },
        deleteRow = { harness.deleteRow(it.ns, it.id) },
        deleteNamespaceRows = { harness.deleteNamespace(it.value) },
        deleteAllRows = harness::deleteAll,
    )
    return SourceOfTruthFaultFixture(source, faults, harness.driver::close)
}

internal fun sqlContractBookkeeperFaultFixture(): BookkeeperFaultFixture {
    val harness = freshHarness()
    val faults = MutationFaultInjector()
    val transacter = TransactionBoundaryFaults(harness.transacter, faults::reachTransactionBoundary)
    return BookkeeperFaultFixture(
        SqlDelightBookkeeper(harness.driver, transacter),
        faults,
        harness.driver::close,
    )
}

internal fun sqlContractStatusFaultFixture(): BookkeeperStatusFaultFixture {
    val harness = freshHarness()
    val driver = StatusReadFaultDriver(harness.driver)
    return BookkeeperStatusFaultFixture(
        SqlDelightBookkeeper(driver, harness.transacter),
        driver::arm,
        harness.driver::close,
    )
}

private fun MutationFaultInjector.reachTransactionBoundary(boundary: TransactionBoundary) {
    reach(
        when (boundary) {
            TransactionBoundary.BeforeCommit -> MutationFaultPoint.BeforeCommit
            TransactionBoundary.AfterCommit -> MutationFaultPoint.AfterCommit
        },
    )
}

private class StatusReadFaultDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    private var callback: (() -> Unit)? = null

    fun arm(action: () -> Unit) {
        check(callback == null)
        callback = action
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val action = callback
        callback = null
        action?.invoke()
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}
