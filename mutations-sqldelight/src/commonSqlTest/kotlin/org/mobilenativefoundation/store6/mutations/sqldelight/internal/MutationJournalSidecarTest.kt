package org.mobilenativefoundation.store6.mutations.sqldelight.internal

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import org.mobilenativefoundation.store6.mutations.sqldelight.JournalHarness
import org.mobilenativefoundation.store6.mutations.sqldelight.freshJournalHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

internal class MutationJournalSidecarTest {
    @Test
    fun schemaInitializationCreatesTheFrozenModelAndLeavesUserVersionUntouched() {
        withHarness { harness ->
            harness.setUserVersion(73L)

            MutationJournalSidecar(harness.driver, harness.transacter)

            assertEquals(2L, harness.schemaVersion())
            assertFrozenSchema(harness)
            assertV2TombstoneSchema(harness)
            assertEquals(73L, harness.userVersion())

            val firstOpen = harness.databaseSnapshot()
            MutationJournalSidecar(harness.driver, harness.transacter)
            assertEquals(firstOpen, harness.databaseSnapshot())
        }

        withHarness { harness ->
            harness.setUserVersion(73L)
            harness.installSchemaV1()
            harness.seedQuiescentExecutions()
            harness.seedActiveTombstone()

            val v1Tables = harness.tableDefinitions()
            val v1Rows = harness.seededDataSnapshot()

            MutationJournalSidecar(harness.driver, harness.transacter)

            assertEquals(2L, harness.schemaVersion())
            assertFrozenSchema(harness)
            assertV2TombstoneSchema(harness)
            assertEquals(
                v1Tables - "store6_key_tombstone",
                harness.tableDefinitions() - "store6_key_tombstone",
            )
            assertNotEquals(
                canonicalTombstoneSql(v1Tables.getValue("store6_key_tombstone")),
                canonicalTombstoneSql(harness.tableSql("store6_key_tombstone")),
            )
            assertEquals(v1Rows, harness.seededDataSnapshot())
            assertEquals(73L, harness.userVersion())

            val migrated = harness.databaseSnapshot()
            MutationJournalSidecar(harness.driver, harness.transacter)
            assertEquals(migrated, harness.databaseSnapshot())
        }
    }

    @Test
    fun newerStoredSchemaVersionFailsFastWithUpgradeOrRestoreDiagnostic() {
        withHarness { harness ->
            MutationJournalSidecar(harness.driver, harness.transacter)
            harness.setUserVersion(73L)
            harness.setSchemaVersion(3L)
            val before = harness.databaseSnapshot()

            val failure =
                assertFailsWith<IllegalStateException> {
                    MutationJournalSidecar(harness.driver, harness.transacter)
                }

            assertEquals(
                "mutations-sqldelight found mutation-journal schema version 3 in this " +
                    "database, but this adapter supports up to 2. Upgrade the " +
                    "mutations-sqldelight dependency for this database, or restore the " +
                    "database.",
                failure.message,
            )
            assertEquals(before, harness.databaseSnapshot())
        }

        NON_QUIESCENT_EXECUTIONS.forEach { owner ->
            withHarness { harness ->
                harness.setUserVersion(73L)
                harness.installSchemaV1()
                harness.seedExecution(owner)
                val before = harness.databaseSnapshot()

                val failure =
                    assertFailsWith<IllegalStateException>(owner.label) {
                        MutationJournalSidecar(harness.driver, harness.transacter)
                    }

                assertEquals(MIGRATION_QUIESCENCE_DIAGNOSTIC, failure.message, owner.label)
                assertEquals(before, harness.databaseSnapshot(), owner.label)
                assertEquals(1L, harness.schemaVersion(), owner.label)
                assertEquals(73L, harness.userVersion(), owner.label)
            }
        }

        withHarness { harness ->
            harness.setUserVersion(73L)
            harness.installSchemaV1()
            harness.seedQuiescentExecutions()
            harness.seedActiveTombstone()
            val before = harness.databaseSnapshot()
            val failingDriver = FailAfterSchemaVersionWriteDriver(harness.driver)
            val failingHarness = JournalHarness(failingDriver)

            assertFailsWith<ForcedMigrationFailure> {
                MutationJournalSidecar(failingDriver, failingHarness.transacter)
            }

            assertEquals(before, harness.databaseSnapshot())
            assertEquals(1L, harness.schemaVersion())
            assertEquals(73L, harness.userVersion())
        }
    }

    @Test
    fun nonPositiveStoredSchemaVersionFailsFast() =
        withHarness { harness ->
            MutationJournalSidecar(harness.driver, harness.transacter)
            harness.setSchemaVersion(0L)

            assertFailsWith<IllegalStateException> {
                MutationJournalSidecar(harness.driver, harness.transacter)
            }
        }

    @Test
    fun schemaEnforcesLandedCarrierRelationsWithoutClosingAppendOnlyEnums() =
        withHarness { harness ->
            MutationJournalSidecar(harness.driver, harness.transacter)

            assertFails {
                harness.executeRaw(
                    """INSERT INTO store6_mutation_execution(
                       client_id, client_sequence, phase, current_generation, attempt,
                       last_attempt_at, active_failure_id, retired_at)
                       VALUES ('client', 1, 'READY', 0, 1, NULL, NULL, NULL)""",
                )
            }
            harness.executeRaw(
                """INSERT INTO store6_key_tombstone(
                   namespace, canonical_id, created_by_client_id, created_by_sequence,
                   state, created_at, activated_at, superseded_by_client_id,
                   superseded_by_sequence, superseded_at)
                   VALUES ('users', 'pending', 'client', 1, 'PENDING', 1, NULL,
                     NULL, NULL, NULL)""",
            )
            assertFails {
                harness.executeRaw(
                    """INSERT INTO store6_key_tombstone(
                       namespace, canonical_id, created_by_client_id, created_by_sequence,
                       state, created_at, activated_at, superseded_by_client_id,
                       superseded_by_sequence, superseded_at)
                       VALUES ('users', 'pending', 'client', 2, 'PENDING', 2, NULL,
                         NULL, NULL, NULL)""",
                )
            }
            assertFails {
                harness.executeRaw(
                    """INSERT INTO store6_mutation_ack(
                       client_id, client_sequence, generation, authoritative_presence,
                       authoritative_blob, value_codec_version, etag,
                       canonical_target_namespace, canonical_target_id, received_at)
                       VALUES ('client', 1, 1, 'PRESENT', NULL, 1, NULL, NULL, NULL, 1)""",
                )
            }
            harness.executeRaw(
                """INSERT INTO store6_key_tombstone(
                   namespace, canonical_id, created_by_client_id, created_by_sequence,
                   state, created_at, activated_at, superseded_by_client_id,
                   superseded_by_sequence, superseded_at)
                   VALUES ('users', 'a', 'client', 2, 'SUPERSEDED', 1, 2,
                     'client', 1, 3)""",
            )

            harness.executeRaw(
                """INSERT INTO store6_mutation_effect(
                   client_id, client_sequence, effect_index, kind, namespace, canonical_id,
                   created_at, disposition, completed_at)
                   VALUES ('client', 1, 0, 'FUTURE_KIND', 'users', NULL, 1,
                     'FUTURE_DISPOSITION', 2)""",
            )
            harness.executeRaw(
                """INSERT INTO store6_key_tombstone(
                   namespace, canonical_id, created_by_client_id, created_by_sequence,
                   state, created_at, activated_at, superseded_by_client_id,
                   superseded_by_sequence, superseded_at)
                   VALUES ('users', 'future', 'client', 1, 'FUTURE_STATE', 1, 2,
                     NULL, NULL, NULL)""",
            )
        }

    private fun assertFrozenSchema(harness: JournalHarness) {
        assertEquals(EXPECTED_COLUMNS.keys, harness.tableNames())
        EXPECTED_COLUMNS.forEach { (table, columns) ->
            assertEquals(columns, harness.tableColumnSignatures(table), table)
            assertEquals(EXPECTED_INDEXES[table].orEmpty(), harness.explicitIndexSignatures(table), table)
        }
    }

    private fun assertV2TombstoneSchema(harness: JournalHarness) {
        val actual = canonicalTombstoneSql(harness.tableSql("store6_key_tombstone"))
        assertEquals(canonicalTombstoneSql(V2_TOMBSTONE_DDL), actual)
        assertFalse(LOWER_LINK_CHECK in actual)
    }

    private fun canonicalTombstoneSql(sql: String): String =
        sql
            .replace("\"store6_key_tombstone\"", "store6_key_tombstone")
            .replace("IF NOT EXISTS ", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun JournalHarness.installSchemaV1() {
        MutationJournalSidecar(driver, transacter)
        executeRaw("DROP TABLE store6_key_tombstone")
        executeRaw(V1_TOMBSTONE_DDL)
        V1_TOMBSTONE_INDEX_DDL.forEach { statement -> executeRaw(statement) }
        setSchemaVersion(1L)
    }

    private fun JournalHarness.seedQuiescentExecutions() {
        seedClient(lastAllocatedSequence = 4L)
        seedIntent(sequence = 1L)
        seedExecutionRow(sequence = 1L, seed = QUIESCENT_EXECUTIONS[0])
        seedIntent(sequence = 2L)
        seedExecutionRow(sequence = 2L, seed = QUIESCENT_EXECUTIONS[1])
        seedIntent(sequence = 3L)
        seedExecutionRow(sequence = 3L, seed = QUIESCENT_EXECUTIONS[2])
        seedIntent(sequence = 4L)
        seedExecutionRow(sequence = 4L, seed = QUIESCENT_EXECUTIONS[3])
        seedRelatedDurableRows()
    }

    private fun JournalHarness.seedRelatedDurableRows() {
        executeRaw(
            """INSERT INTO store6_mutation_attempt(
               client_id, client_sequence, generation, effective_namespace,
               effective_canonical_id, value_codec_version, base_presence, base_blob,
               mine_presence, mine_blob, precondition_meta_present, precondition_written_at,
               precondition_etag, advertised_retired_through_sequence,
               generation_idempotency_key, prepared_at, conflict_meta_present,
               conflict_written_at, conflict_etag, conflict_received_at)
               VALUES ('client', 2, 1, 'users', 'key-2', 1, 'ABSENT', NULL,
                 'PRESENT', X'02', 0, NULL, NULL, 0, 'idempotency-2-1', 2,
                 NULL, NULL, NULL, NULL)""",
        )
        executeRaw(
            """INSERT INTO store6_mutation_ack(
               client_id, client_sequence, generation, authoritative_presence,
               authoritative_blob, value_codec_version, etag,
               canonical_target_namespace, canonical_target_id, received_at)
               VALUES ('client', 4, 1, 'PRESENT', X'04', 1, 'etag-4', NULL, NULL, 4)""",
        )
        executeRaw(
            """INSERT INTO store6_mutation_failure(
               failure_id, client_id, client_sequence, generation, kind,
               detail, message, occurred_at)
               VALUES (31, 'client', 3, 1, 'TRANSPORT', 'seeded', 'seeded failure', 3)""",
        )
        executeRaw(
            """INSERT INTO store6_mutation_effect(
               client_id, client_sequence, effect_index, kind, namespace,
               canonical_id, created_at, disposition, completed_at)
               VALUES ('client', 4, 0, 'KEY', 'users', 'effect-4', 4, 'APPLIED', 5)""",
        )
        executeRaw(
            """INSERT INTO store6_key_alias(
               source_namespace, source_canonical_id, target_namespace, target_canonical_id,
               state, created_by_client_id, created_by_sequence, created_at, activated_at)
               VALUES ('users', 'alias-source', 'users', 'alias-target', 'ACTIVE',
                 'client', 4, 4, 5)""",
        )
    }

    private fun JournalHarness.seedExecution(seed: ExecutionSeed) {
        seedClient(lastAllocatedSequence = 1L)
        seedIntent(sequence = 1L)
        seedExecutionRow(sequence = 1L, seed = seed)
    }

    private fun JournalHarness.seedClient(lastAllocatedSequence: Long) {
        executeRaw(
            """INSERT INTO store6_mutation_client(
               record_version, client_id, last_allocated_sequence,
               retired_through_sequence, server_confirmed_retired_through_sequence, created_at)
               VALUES (1, 'client', $lastAllocatedSequence, 0, 0, 1)""",
        )
    }

    private fun JournalHarness.seedIntent(sequence: Long) {
        executeRaw(
            """INSERT INTO store6_mutation_intent(
               row_id, record_version, client_id, client_sequence, mutation_id, namespace,
               canonical_id, mutator_id, mutator_version, args_blob, idempotency_root, created_at)
               VALUES ($sequence, 1, 'client', $sequence, 'mutation-$sequence', 'users',
                 'key-$sequence', 'mutator', 1, X'00', 'root-$sequence', 1)""",
        )
    }

    private fun JournalHarness.seedExecutionRow(
        sequence: Long,
        seed: ExecutionSeed,
    ) {
        val lastAttemptAt = seed.lastAttemptAt?.toString() ?: "NULL"
        val activeFailureId = seed.activeFailureId?.toString() ?: "NULL"
        val retiredAt = seed.retiredAt?.toString() ?: "NULL"
        executeRaw(
            """INSERT INTO store6_mutation_execution(
               client_id, client_sequence, phase, current_generation, attempt,
               last_attempt_at, active_failure_id, retired_at)
               VALUES ('client', $sequence, '${seed.phase}', ${seed.currentGeneration},
                 ${seed.attempt}, $lastAttemptAt, $activeFailureId, $retiredAt)""",
        )
    }

    private fun JournalHarness.seedActiveTombstone() {
        executeRaw(
            """INSERT INTO store6_key_tombstone(
               namespace, canonical_id, created_by_client_id, created_by_sequence,
               state, created_at, activated_at, superseded_by_client_id,
               superseded_by_sequence, superseded_at)
               VALUES ('users', 'seeded', 'client', 4, 'ACTIVE', 10, 11,
                 NULL, NULL, NULL)""",
        )
    }

    private fun JournalHarness.tableDefinitions(): Map<String, String> =
        EXPECTED_COLUMNS.keys.associateWith { table -> tableSql(table) }

    private fun JournalHarness.tableSql(table: String): String =
        queryTextList("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'").single()

    private fun JournalHarness.databaseSnapshot(): SidecarSnapshot =
        SidecarSnapshot(
            schemaObjects = schemaObjectSnapshot(),
            schemaVersion = schemaVersion(),
            seededRows = seededDataSnapshot(),
            userVersion = userVersion(),
        )

    private fun JournalHarness.schemaObjectSnapshot(): List<String> =
        queryTextList(
            """SELECT type || '|' || name || '|' || tbl_name || '|' || COALESCE(sql, '<null>')
               FROM sqlite_master
               WHERE type IN ('table', 'index')
                 AND (tbl_name LIKE 'store6_mutation_%' OR tbl_name LIKE 'store6_key_%')
               ORDER BY type, name""",
        )

    private fun JournalHarness.seededDataSnapshot(): List<String> =
        queryTextList(
            """SELECT 'client|' || quote(record_version) || '|' || quote(client_id) || '|' ||
                 quote(last_allocated_sequence) || '|' || quote(retired_through_sequence) || '|' ||
                 quote(server_confirmed_retired_through_sequence) || '|' || quote(created_at)
               FROM store6_mutation_client
               UNION ALL
               SELECT 'intent|' || quote(row_id) || '|' || quote(record_version) || '|' ||
                 quote(client_id) || '|' || quote(client_sequence) || '|' || quote(mutation_id) ||
                 '|' || quote(namespace) || '|' || quote(canonical_id) || '|' || quote(mutator_id) ||
                 '|' || quote(mutator_version) || '|' || quote(args_blob) || '|' ||
                 quote(idempotency_root) || '|' || quote(created_at)
               FROM store6_mutation_intent
               UNION ALL
               SELECT 'execution|' || quote(client_id) || '|' || quote(client_sequence) || '|' ||
                 quote(phase) || '|' || quote(current_generation) || '|' || quote(attempt) || '|' ||
                 quote(last_attempt_at) || '|' || quote(active_failure_id) || '|' || quote(retired_at)
               FROM store6_mutation_execution
               UNION ALL
               SELECT 'attempt|' || quote(client_id) || '|' || quote(client_sequence) || '|' ||
                 quote(generation) || '|' || quote(effective_namespace) || '|' ||
                 quote(effective_canonical_id) || '|' || quote(value_codec_version) || '|' ||
                 quote(base_presence) || '|' || quote(base_blob) || '|' || quote(mine_presence) ||
                 '|' || quote(mine_blob) || '|' || quote(precondition_meta_present) || '|' ||
                 quote(precondition_written_at) || '|' || quote(precondition_etag) || '|' ||
                 quote(advertised_retired_through_sequence) || '|' ||
                 quote(generation_idempotency_key) || '|' || quote(prepared_at) || '|' ||
                 quote(conflict_meta_present) || '|' || quote(conflict_written_at) || '|' ||
                 quote(conflict_etag) || '|' || quote(conflict_received_at)
               FROM store6_mutation_attempt
               UNION ALL
               SELECT 'ack|' || quote(client_id) || '|' || quote(client_sequence) || '|' ||
                 quote(generation) || '|' || quote(authoritative_presence) || '|' ||
                 quote(authoritative_blob) || '|' || quote(value_codec_version) || '|' ||
                 quote(etag) || '|' || quote(canonical_target_namespace) || '|' ||
                 quote(canonical_target_id) || '|' || quote(received_at)
               FROM store6_mutation_ack
               UNION ALL
               SELECT 'failure|' || quote(failure_id) || '|' || quote(client_id) || '|' ||
                 quote(client_sequence) || '|' || quote(generation) || '|' || quote(kind) || '|' ||
                 quote(detail) || '|' || quote(message) || '|' || quote(occurred_at)
               FROM store6_mutation_failure
               UNION ALL
               SELECT 'effect|' || quote(client_id) || '|' || quote(client_sequence) || '|' ||
                 quote(effect_index) || '|' || quote(kind) || '|' || quote(namespace) || '|' ||
                 quote(canonical_id) || '|' || quote(created_at) || '|' || quote(disposition) ||
                 '|' || quote(completed_at)
               FROM store6_mutation_effect
               UNION ALL
               SELECT 'alias|' || quote(source_namespace) || '|' || quote(source_canonical_id) ||
                 '|' || quote(target_namespace) || '|' || quote(target_canonical_id) || '|' ||
                 quote(state) || '|' || quote(created_by_client_id) || '|' ||
                 quote(created_by_sequence) || '|' || quote(created_at) || '|' || quote(activated_at)
               FROM store6_key_alias
               UNION ALL
               SELECT 'tombstone|' || quote(namespace) || '|' || quote(canonical_id) || '|' ||
                 quote(created_by_client_id) || '|' || quote(created_by_sequence) || '|' ||
                 quote(state) || '|' || quote(created_at) || '|' || quote(activated_at) || '|' ||
                 quote(superseded_by_client_id) || '|' || quote(superseded_by_sequence) || '|' ||
                 quote(superseded_at)
               FROM store6_key_tombstone
               ORDER BY 1""",
        )

    private fun JournalHarness.queryTextList(sql: String): List<String> =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val result = mutableListOf<String>()
                while (cursor.next().value) result += cursor.getString(0)!!
                QueryResult.Value(result)
            },
            0,
            {},
        ).value

    private fun withHarness(block: (JournalHarness) -> Unit) {
        val harness = freshJournalHarness()
        try {
            block(harness)
        } finally {
            harness.driver.close()
        }
    }

    private data class ExecutionSeed(
        val label: String,
        val phase: String,
        val currentGeneration: Long,
        val attempt: Long,
        val lastAttemptAt: Long? = null,
        val activeFailureId: Long? = null,
        val retiredAt: Long? = null,
    )

    private data class SidecarSnapshot(
        val schemaObjects: List<String>,
        val schemaVersion: Long?,
        val seededRows: List<String>,
        val userVersion: Long,
    )

    private class ForcedMigrationFailure : RuntimeException("forced failure after schema-version write")

    private class FailAfterSchemaVersionWriteDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        private var armed = true

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            val result = delegate.execute(identifier, sql, parameters, binders)
            if (armed && "UPDATE store6_mutation_schema SET version" in sql) {
                armed = false
                throw ForcedMigrationFailure()
            }
            return result
        }
    }

    private companion object {
        const val MIGRATION_QUIESCENCE_DIAGNOSTIC: String =
            "mutations-sqldelight cannot migrate mutation-journal schema version 1 to 2 " +
                "because durable mutation namespaces are not quiescent. Downgrade the " +
                "mutations-sqldelight dependency to a version that supports schema " +
                "version 1, drain or park/retire every non-quiescent mutation namespace, then " +
                "retry the upgrade."

        const val LOWER_LINK_CHECK: String =
            "CHECK (superseded_by_client_id IS NULL " +
                "OR superseded_by_client_id <> created_by_client_id " +
                "OR superseded_by_sequence > created_by_sequence)"

        val V1_TOMBSTONE_DDL: String =
            """CREATE TABLE store6_key_tombstone(
               namespace TEXT NOT NULL,
               canonical_id TEXT NOT NULL,
               created_by_client_id TEXT NOT NULL,
               created_by_sequence INTEGER NOT NULL CHECK (created_by_sequence > 0),
               state TEXT NOT NULL,
               created_at INTEGER NOT NULL,
               activated_at INTEGER,
               superseded_by_client_id TEXT,
               superseded_by_sequence INTEGER,
               superseded_at INTEGER,
               PRIMARY KEY (namespace, canonical_id, created_by_client_id, created_by_sequence),
               CHECK ((superseded_by_client_id IS NULL) = (superseded_by_sequence IS NULL)),
               CHECK (superseded_by_sequence IS NULL OR superseded_by_sequence > 0),
               CHECK (superseded_by_client_id IS NULL
                 OR superseded_by_client_id <> created_by_client_id
                 OR superseded_by_sequence > created_by_sequence),
               CHECK ((state = 'PENDING') = (activated_at IS NULL)),
               CHECK ((state = 'SUPERSEDED') = (superseded_by_client_id IS NOT NULL)),
               CHECK ((state = 'SUPERSEDED') = (superseded_at IS NOT NULL)))"""

        val V2_TOMBSTONE_DDL: String =
            """CREATE TABLE store6_key_tombstone(
               namespace TEXT NOT NULL,
               canonical_id TEXT NOT NULL,
               created_by_client_id TEXT NOT NULL,
               created_by_sequence INTEGER NOT NULL CHECK (created_by_sequence > 0),
               state TEXT NOT NULL,
               created_at INTEGER NOT NULL,
               activated_at INTEGER,
               superseded_by_client_id TEXT,
               superseded_by_sequence INTEGER,
               superseded_at INTEGER,
               PRIMARY KEY (namespace, canonical_id, created_by_client_id, created_by_sequence),
               CHECK ((superseded_by_client_id IS NULL) = (superseded_by_sequence IS NULL)),
               CHECK (superseded_by_sequence IS NULL OR superseded_by_sequence > 0),
               CHECK ((state = 'PENDING') = (activated_at IS NULL)),
               CHECK ((state = 'SUPERSEDED') = (superseded_by_client_id IS NOT NULL)),
               CHECK ((state = 'SUPERSEDED') = (superseded_at IS NOT NULL)))"""

        val V1_TOMBSTONE_INDEX_DDL: List<String> =
            listOf(
                """CREATE INDEX store6_key_tombstone_state
                   ON store6_key_tombstone(namespace, canonical_id, state)""",
                """CREATE UNIQUE INDEX store6_key_tombstone_one_pending
                   ON store6_key_tombstone(namespace, canonical_id) WHERE state = 'PENDING'""",
                """CREATE UNIQUE INDEX store6_key_tombstone_one_active
                   ON store6_key_tombstone(namespace, canonical_id) WHERE state = 'ACTIVE'""",
            )

        val QUIESCENT_EXECUTIONS: List<ExecutionSeed> =
            listOf(
                ExecutionSeed("UNPREPARED", "UNPREPARED", currentGeneration = 0L, attempt = 0L),
                ExecutionSeed("first-generation READY", "READY", currentGeneration = 1L, attempt = 0L),
                ExecutionSeed(
                    "PARKED",
                    "PARKED",
                    currentGeneration = 1L,
                    attempt = 0L,
                    activeFailureId = 31L,
                ),
                ExecutionSeed(
                    "RETIRED",
                    "RETIRED",
                    currentGeneration = 1L,
                    attempt = 0L,
                    retiredAt = 4L,
                ),
            )

        val NON_QUIESCENT_EXECUTIONS: List<ExecutionSeed> =
            listOf(
                ExecutionSeed("INFLIGHT", "INFLIGHT", currentGeneration = 1L, attempt = 0L),
                ExecutionSeed(
                    "REFRESH_REQUIRED",
                    "REFRESH_REQUIRED",
                    currentGeneration = 1L,
                    attempt = 0L,
                ),
                ExecutionSeed("ACKED", "ACKED", currentGeneration = 1L, attempt = 0L),
                ExecutionSeed(
                    "EFFECTS_PENDING",
                    "EFFECTS_PENDING",
                    currentGeneration = 1L,
                    attempt = 0L,
                ),
                ExecutionSeed(
                    "post-attempt READY",
                    "READY",
                    currentGeneration = 1L,
                    attempt = 1L,
                    lastAttemptAt = 2L,
                ),
                ExecutionSeed(
                    "generation > 1 READY",
                    "READY",
                    currentGeneration = 2L,
                    attempt = 0L,
                ),
            )

        val EXPECTED_COLUMNS: Map<String, List<String>> =
            mapOf(
                "store6_mutation_schema" to
                    listOf(
                        "id:INTEGER:1:1",
                        "version:INTEGER:1:0",
                    ),
                "store6_mutation_client" to
                    listOf(
                        "record_version:INTEGER:1:0",
                        "client_id:TEXT:1:1",
                        "last_allocated_sequence:INTEGER:1:0",
                        "retired_through_sequence:INTEGER:1:0",
                        "server_confirmed_retired_through_sequence:INTEGER:1:0",
                        "created_at:INTEGER:1:0",
                    ),
                "store6_mutation_intent" to
                    listOf(
                        "row_id:INTEGER:0:1",
                        "record_version:INTEGER:1:0",
                        "client_id:TEXT:1:0",
                        "client_sequence:INTEGER:1:0",
                        "mutation_id:TEXT:1:0",
                        "namespace:TEXT:1:0",
                        "canonical_id:TEXT:1:0",
                        "mutator_id:TEXT:1:0",
                        "mutator_version:INTEGER:1:0",
                        "args_blob:BLOB:1:0",
                        "idempotency_root:TEXT:1:0",
                        "created_at:INTEGER:1:0",
                    ),
                "store6_mutation_execution" to
                    listOf(
                        "client_id:TEXT:1:1",
                        "client_sequence:INTEGER:1:2",
                        "phase:TEXT:1:0",
                        "current_generation:INTEGER:1:0",
                        "attempt:INTEGER:1:0",
                        "last_attempt_at:INTEGER:0:0",
                        "active_failure_id:INTEGER:0:0",
                        "retired_at:INTEGER:0:0",
                    ),
                "store6_mutation_attempt" to
                    listOf(
                        "client_id:TEXT:1:1",
                        "client_sequence:INTEGER:1:2",
                        "generation:INTEGER:1:3",
                        "effective_namespace:TEXT:1:0",
                        "effective_canonical_id:TEXT:1:0",
                        "value_codec_version:INTEGER:1:0",
                        "base_presence:TEXT:1:0",
                        "base_blob:BLOB:0:0",
                        "mine_presence:TEXT:1:0",
                        "mine_blob:BLOB:0:0",
                        "precondition_meta_present:INTEGER:1:0",
                        "precondition_written_at:INTEGER:0:0",
                        "precondition_etag:TEXT:0:0",
                        "advertised_retired_through_sequence:INTEGER:1:0",
                        "generation_idempotency_key:TEXT:1:0",
                        "prepared_at:INTEGER:1:0",
                        "conflict_meta_present:INTEGER:0:0",
                        "conflict_written_at:INTEGER:0:0",
                        "conflict_etag:TEXT:0:0",
                        "conflict_received_at:INTEGER:0:0",
                    ),
                "store6_mutation_ack" to
                    listOf(
                        "client_id:TEXT:1:1",
                        "client_sequence:INTEGER:1:2",
                        "generation:INTEGER:1:3",
                        "authoritative_presence:TEXT:1:0",
                        "authoritative_blob:BLOB:0:0",
                        "value_codec_version:INTEGER:1:0",
                        "etag:TEXT:0:0",
                        "canonical_target_namespace:TEXT:0:0",
                        "canonical_target_id:TEXT:0:0",
                        "received_at:INTEGER:1:0",
                    ),
                "store6_mutation_failure" to
                    listOf(
                        "failure_id:INTEGER:0:1",
                        "client_id:TEXT:1:0",
                        "client_sequence:INTEGER:1:0",
                        "generation:INTEGER:1:0",
                        "kind:TEXT:1:0",
                        "detail:TEXT:1:0",
                        "message:TEXT:1:0",
                        "occurred_at:INTEGER:1:0",
                    ),
                "store6_mutation_effect" to
                    listOf(
                        "client_id:TEXT:1:1",
                        "client_sequence:INTEGER:1:2",
                        "effect_index:INTEGER:1:3",
                        "kind:TEXT:1:0",
                        "namespace:TEXT:1:0",
                        "canonical_id:TEXT:0:0",
                        "created_at:INTEGER:1:0",
                        "disposition:TEXT:1:0",
                        "completed_at:INTEGER:0:0",
                    ),
                "store6_key_alias" to
                    listOf(
                        "source_namespace:TEXT:1:1",
                        "source_canonical_id:TEXT:1:2",
                        "target_namespace:TEXT:1:0",
                        "target_canonical_id:TEXT:1:0",
                        "state:TEXT:1:0",
                        "created_by_client_id:TEXT:1:0",
                        "created_by_sequence:INTEGER:1:0",
                        "created_at:INTEGER:1:0",
                        "activated_at:INTEGER:0:0",
                    ),
                "store6_key_tombstone" to
                    listOf(
                        "namespace:TEXT:1:1",
                        "canonical_id:TEXT:1:2",
                        "created_by_client_id:TEXT:1:3",
                        "created_by_sequence:INTEGER:1:4",
                        "state:TEXT:1:0",
                        "created_at:INTEGER:1:0",
                        "activated_at:INTEGER:0:0",
                        "superseded_by_client_id:TEXT:0:0",
                        "superseded_by_sequence:INTEGER:0:0",
                        "superseded_at:INTEGER:0:0",
                    ),
            )

        val EXPECTED_INDEXES: Map<String, Set<String>> =
            mapOf(
                "store6_mutation_intent" to
                    setOf(
                        "store6_mutation_intent_identity:unique=0:partial=0:namespace,canonical_id,client_id,client_sequence",
                    ),
                "store6_mutation_execution" to
                    setOf(
                        "store6_mutation_execution_phase:unique=0:partial=0:client_id,phase,client_sequence",
                    ),
                "store6_mutation_failure" to
                    setOf(
                        "store6_mutation_failure_prune:unique=0:partial=0:client_id,client_sequence,failure_id",
                        "store6_mutation_failure_order:unique=0:partial=0:client_id,failure_id",
                    ),
                "store6_key_tombstone" to
                    setOf(
                        "store6_key_tombstone_state:unique=0:partial=0:namespace,canonical_id,state",
                        "store6_key_tombstone_one_pending:unique=1:partial=1:namespace,canonical_id",
                        "store6_key_tombstone_one_active:unique=1:partial=1:namespace,canonical_id",
                    ),
            )
    }
}
