package org.mobilenativefoundation.store6.mutations.sqldelight.internal

import org.mobilenativefoundation.store6.mutations.sqldelight.JournalHarness
import org.mobilenativefoundation.store6.mutations.sqldelight.freshJournalHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

internal class MutationJournalSidecarTest {
    @Test
    fun schemaInitializationCreatesTheFrozenModelAndLeavesUserVersionUntouched() =
        withHarness { harness ->
            harness.setUserVersion(73L)

            MutationJournalSidecar(harness.driver, harness.transacter)
            MutationJournalSidecar(harness.driver, harness.transacter)

            assertEquals(EXPECTED_COLUMNS.keys, harness.tableNames())
            EXPECTED_COLUMNS.forEach { (table, columns) ->
                assertEquals(columns, harness.tableColumnSignatures(table), table)
                assertEquals(EXPECTED_INDEXES[table].orEmpty(), harness.explicitIndexSignatures(table), table)
            }
            assertEquals(1L, harness.schemaVersion())
            assertEquals(73L, harness.userVersion())
        }

    @Test
    fun newerStoredSchemaVersionFailsFastWithUpgradeOrRestoreDiagnostic() =
        withHarness { harness ->
            MutationJournalSidecar(harness.driver, harness.transacter)
            harness.setSchemaVersion(2L)

            val failure =
                assertFailsWith<IllegalStateException> {
                    MutationJournalSidecar(harness.driver, harness.transacter)
                }

            assertEquals(
                "store6-mutations-sqldelight found mutation-journal schema version 2 in this " +
                    "database, but this adapter supports up to 1. Upgrade the " +
                    "store6-mutations-sqldelight dependency for this database, or restore the " +
                    "database.",
                failure.message,
            )
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
            assertFails {
                harness.executeRaw(
                    """INSERT INTO store6_key_tombstone(
                       namespace, canonical_id, created_by_client_id, created_by_sequence,
                       state, created_at, activated_at, superseded_by_client_id,
                       superseded_by_sequence, superseded_at)
                       VALUES ('users', 'a', 'client', 2, 'SUPERSEDED', 1, 2,
                         'client', 1, 3)""",
                )
            }

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

    private fun withHarness(block: (JournalHarness) -> Unit) {
        val harness = freshJournalHarness()
        try {
            block(harness)
        } finally {
            harness.driver.close()
        }
    }

    private companion object {
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
