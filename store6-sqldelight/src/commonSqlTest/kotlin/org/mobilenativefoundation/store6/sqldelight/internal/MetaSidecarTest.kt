@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight.internal

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.sqldelight.MetaRow
import org.mobilenativefoundation.store6.sqldelight.SqlHarness
import org.mobilenativefoundation.store6.sqldelight.freshHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MetaSidecarTest {
    @Test
    fun androidMinSdkSqliteCompatibilityAvoidsPost39UpsertSyntax() =
        withHarness { harness ->
            val legacyDriver = RejectingPost39SyntaxDriver(harness.driver)
            val transacter = TestTransacter(legacyDriver)
            val sidecar = MetaSidecar(legacyDriver, transacter)

            transacter.transaction {
                sidecar.stampWrite("users", "a", 10L)
                sidecar.recordSuccess("users", "a", TestMeta(20L, "v1"))
                sidecar.recordFailure("users", "a", 30L)
                sidecar.markStale("users", "a")
                sidecar.advanceNamespaceWatermark("users")
            }

            assertEquals(
                MetaRow(
                    writtenAtEpochMillis = 20L,
                    etag = "v1",
                    successSequence = 1L,
                    staleSequence = 2L,
                    failureAtEpochMillis = 30L,
                    consecutiveFailures = 1,
                ),
                harness.metaRow("users", "a"),
            )
            assertEquals(3L, harness.metaSequence())
            assertEquals(3L, harness.watermark("ns:users"))
        }

    @Test
    fun schemaInitializationIsIdempotentAndLeavesUserVersionUntouched() =
        withHarness { harness ->
            harness.executeRaw("PRAGMA user_version = 73")

            MetaSidecar(harness.driver, harness.transacter)
            MetaSidecar(harness.driver, harness.transacter)

            assertEquals(
                setOf(
                    "store6_meta",
                    "store6_meta_schema",
                    "store6_meta_sequence",
                    "store6_meta_watermark",
                ),
                harness.store6TableNames(),
            )
            assertEquals(1L, harness.metaSchemaVersion())
            assertEquals(0L, harness.metaSequence())
            assertEquals(73L, harness.userVersion())
        }

    @Test
    fun newerStoredSchemaVersionFailsFastWithUpgradeOrRestoreDiagnostic() =
        withHarness { harness ->
            MetaSidecar(harness.driver, harness.transacter)
            harness.setMetaSchemaVersion(2L)

            val failure =
                assertFailsWith<IllegalStateException> {
                    MetaSidecar(harness.driver, harness.transacter)
                }

            assertEquals(
                "store6-sqldelight found durable-meta schema version 2 in this database, but this " +
                    "adapter supports up to 1. Upgrade the store6-sqldelight dependency for this " +
                    "database, or restore the database.",
                failure.message,
            )
        }

    @Test
    fun nextSequenceIsMonotoneAndRejectsLongMaxValue() =
        withHarness { harness ->
            val sidecar = MetaSidecar(harness.driver, harness.transacter)

            assertEquals(1L, harness.transactionWithResult { sidecar.nextSequence() })
            assertEquals(2L, harness.transactionWithResult { sidecar.nextSequence() })
            assertEquals(2L, harness.metaSequence())

            harness.setMetaSequence(Long.MAX_VALUE)
            val failure =
                assertFailsWith<IllegalStateException> {
                    harness.transactionWithResult { sidecar.nextSequence() }
                }
            assertEquals("Bookkeeper sequence exhausted", failure.message)
            assertEquals(Long.MAX_VALUE, harness.metaSequence())
        }

    @Test
    fun stampWriteChangesWrittenTimeAndNullsEtagWhilePreservingBookkeeping() =
        withHarness { harness ->
            val sidecar = MetaSidecar(harness.driver, harness.transacter)
            harness.transaction {
                sidecar.recordSuccess("users", "a", TestMeta(10L, "v1"))
                sidecar.markStale("users", "a")
                sidecar.recordFailure("users", "a", 30L)
                sidecar.stampWrite("users", "a", 40L)
            }

            assertEquals(
                MetaRow(
                    writtenAtEpochMillis = 40L,
                    etag = null,
                    successSequence = 1L,
                    staleSequence = 2L,
                    failureAtEpochMillis = 30L,
                    consecutiveFailures = 1,
                ),
                harness.metaRow("users", "a"),
            )
            assertEquals(2L, harness.metaSequence())
        }

    @Test
    fun recordSuccessPreservesStaleMarkAdvancesSuccessAndClearsFailures() =
        withHarness { harness ->
            val sidecar = MetaSidecar(harness.driver, harness.transacter)
            harness.transaction {
                sidecar.markStale("users", "a")
                sidecar.recordFailure("users", "a", 20L)
                sidecar.recordSuccess("users", "a", TestMeta(30L, "v2"))
            }

            assertEquals(
                MetaRow(
                    writtenAtEpochMillis = 30L,
                    etag = "v2",
                    successSequence = 2L,
                    staleSequence = 1L,
                    failureAtEpochMillis = null,
                    consecutiveFailures = 0,
                ),
                harness.metaRow("users", "a"),
            )
            val status = harness.transactionWithResult { sidecar.readStatus("users", "a") }
            assertFalse(status!!.durablyStale)
        }

    @Test
    fun recordFailureIncrementsWithoutAllocatingASequence() =
        withHarness { harness ->
            val sidecar = MetaSidecar(harness.driver, harness.transacter)
            harness.transaction {
                sidecar.recordFailure("users", "a", 10L)
                sidecar.recordFailure("users", "a", 20L)
            }

            assertEquals(0L, harness.metaSequence())
            assertEquals(
                StatusShape(
                    meta = null,
                    lastSuccessSequence = null,
                    lastFailureAtEpochMillis = 20L,
                    consecutiveFailures = 2,
                    durablyStale = false,
                ),
                harness.transactionWithResult { sidecar.readStatus("users", "a") }.toShape(),
            )
        }

    @Test
    fun readStatusUsesExactKeyNamespaceAndGlobalWatermarkAlgebra() {
        val watermarkOnly =
            StatusShape(
                meta = null,
                lastSuccessSequence = null,
                lastFailureAtEpochMillis = null,
                consecutiveFailures = 0,
                durablyStale = true,
            )
        val cases =
            listOf(
                StatusCase(
                    name = "absent row and no watermark",
                    setup = {},
                    namespace = "users",
                    id = "a",
                    expected = null,
                ),
                StatusCase(
                    name = "key stale mark",
                    setup = { markStale("users", "a") },
                    namespace = "users",
                    id = "a",
                    expected = watermarkOnly,
                ),
                StatusCase(
                    name = "matching namespace watermark",
                    setup = { advanceNamespaceWatermark("users") },
                    namespace = "users",
                    id = "a",
                    expected = watermarkOnly,
                ),
                StatusCase(
                    name = "namespace watermark isolation",
                    setup = { advanceNamespaceWatermark("users") },
                    namespace = "teams",
                    id = "a",
                    expected = null,
                ),
                StatusCase(
                    name = "global watermark coverage",
                    setup = { advanceGlobalWatermark() },
                    namespace = "teams",
                    id = "a",
                    expected = watermarkOnly,
                ),
                StatusCase(
                    name = "later success outranks earlier watermarks",
                    setup = {
                        advanceNamespaceWatermark("users")
                        advanceGlobalWatermark()
                        recordSuccess("users", "a", TestMeta(50L, "fresh"))
                    },
                    namespace = "users",
                    id = "a",
                    expected =
                        StatusShape(
                            meta = MetaShape(50L, "fresh"),
                            lastSuccessSequence = 3L,
                            lastFailureAtEpochMillis = null,
                            consecutiveFailures = 0,
                            durablyStale = false,
                        ),
                ),
            )

        cases.forEach { case ->
            withHarness { harness ->
                val sidecar = MetaSidecar(harness.driver, harness.transacter)
                harness.transaction { case.setup(sidecar) }
                val actual =
                    harness.transactionWithResult {
                        sidecar.readStatus(case.namespace, case.id)
                    }.toShape()
                assertEquals(case.expected, actual, case.name)
            }
        }
    }

    @Test
    fun rowDeletesNeverResetSequenceOrWatermarks() =
        withHarness { harness ->
            val sidecar = MetaSidecar(harness.driver, harness.transacter)
            harness.transaction {
                sidecar.recordSuccess("users", "a", TestMeta(1L, null))
                sidecar.recordSuccess("users", "b", TestMeta(2L, null))
                sidecar.recordSuccess("teams", "a", TestMeta(3L, null))
                sidecar.advanceGlobalWatermark()
                sidecar.deleteRow("users", "a")
                sidecar.deleteNamespaceRows("users")
                sidecar.deleteAllRows()
            }

            assertNull(harness.metaRow("users", "a"))
            assertNull(harness.metaRow("users", "b"))
            assertNull(harness.metaRow("teams", "a"))
            assertEquals(4L, harness.metaSequence())
            assertEquals(4L, harness.watermark("global"))
        }

    @Test
    fun forgetOperationsDeleteRecordsButNeverResetWatermarksOrSequence() =
        withHarness { harness ->
            val sidecar = MetaSidecar(harness.driver, harness.transacter)
            harness.transaction {
                sidecar.recordSuccess("users", "a", TestMeta(1L, null))
                sidecar.recordSuccess("users", "b", TestMeta(2L, null))
                sidecar.recordSuccess("teams", "a", TestMeta(3L, null))
                sidecar.advanceNamespaceWatermark("users")
                sidecar.forget("users", "a")
                sidecar.forgetNamespace("users")
                sidecar.forgetAll()
            }

            assertNull(harness.metaRow("users", "a"))
            assertNull(harness.metaRow("users", "b"))
            assertNull(harness.metaRow("teams", "a"))
            assertEquals(4L, harness.metaSequence())
            assertEquals(4L, harness.watermark("ns:users"))
        }

    private data class TestMeta(
        override val writtenAtEpochMillis: Long,
        override val etag: String?,
    ) : StoreMeta

    private data class StatusCase(
        val name: String,
        val setup: MetaSidecar.() -> Unit,
        val namespace: String,
        val id: String,
        val expected: StatusShape?,
    )

    private data class MetaShape(
        val writtenAtEpochMillis: Long,
        val etag: String?,
    )

    private data class StatusShape(
        val meta: MetaShape?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val durablyStale: Boolean,
    )

    private fun KeyStatus?.toShape(): StatusShape? =
        this?.let { status ->
            StatusShape(
                meta =
                    status.meta?.let {
                        MetaShape(it.writtenAtEpochMillis, it.etag)
                    },
                lastSuccessSequence = status.lastSuccessSequence,
                lastFailureAtEpochMillis = status.lastFailureAtEpochMillis,
                consecutiveFailures = status.consecutiveFailures,
                durablyStale = status.durablyStale,
            )
        }

    private inline fun <R> withHarness(block: (SqlHarness) -> R): R {
        val harness = freshHarness()
        return try {
            block(harness)
        } finally {
            harness.driver.close()
        }
    }

    private fun SqlHarness.transaction(block: () -> Unit) {
        transacter.transaction { block() }
    }

    private fun <R> SqlHarness.transactionWithResult(block: () -> R): R =
        transacter.transactionWithResult { block() }
}

private class RejectingPost39SyntaxDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if ("DO UPDATE" in sql) {
            throw LegacySqliteSyntaxRejected(
                "Android minSdk SQLite rejected post-3.9 SQL: DO UPDATE",
            )
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }
}

private class TestTransacter(driver: SqlDriver) : TransacterImpl(driver)

private class LegacySqliteSyntaxRejected(message: String) : IllegalStateException(message)
