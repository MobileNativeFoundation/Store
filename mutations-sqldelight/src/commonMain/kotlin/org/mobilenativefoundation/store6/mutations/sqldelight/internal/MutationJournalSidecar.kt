package org.mobilenativefoundation.store6.mutations.sqldelight.internal

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** Adapter-owned mutation-journal schema. SQLite `user_version` remains user-owned. */
internal class MutationJournalSidecar(
    private val driver: SqlDriver,
    transacter: Transacter,
) {
    init {
        transacter.transaction { ensureSchema() }
    }

    private fun ensureSchema() {
        if (!mutationSchemaTableExists()) {
            createSchemaV2()
            return
        }

        val version = queryLong("SELECT version FROM store6_mutation_schema WHERE id = 0")
        if (version == null || version !in 1L..SCHEMA_VERSION) {
            unsupportedSchemaVersion(version)
        }

        when (version) {
            1L -> migrateV1ToV2()
            SCHEMA_VERSION -> createSchemaV2()
        }
    }

    private fun createSchemaV2() {
        executeAll(
            """CREATE TABLE IF NOT EXISTS store6_mutation_schema(
               id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0),
               version INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS store6_mutation_client(
               record_version INTEGER NOT NULL CHECK (record_version > 0),
               client_id TEXT NOT NULL PRIMARY KEY,
               last_allocated_sequence INTEGER NOT NULL,
               retired_through_sequence INTEGER NOT NULL,
               server_confirmed_retired_through_sequence INTEGER NOT NULL,
               created_at INTEGER NOT NULL,
               CHECK (0 <= server_confirmed_retired_through_sequence
                 AND server_confirmed_retired_through_sequence <= retired_through_sequence
                 AND retired_through_sequence <= last_allocated_sequence))""",
            """CREATE TABLE IF NOT EXISTS store6_mutation_intent(
               row_id INTEGER PRIMARY KEY,
               record_version INTEGER NOT NULL CHECK (record_version > 0),
               client_id TEXT NOT NULL,
               client_sequence INTEGER NOT NULL CHECK (client_sequence > 0),
               mutation_id TEXT NOT NULL,
               namespace TEXT NOT NULL,
               canonical_id TEXT NOT NULL,
               mutator_id TEXT NOT NULL,
               mutator_version INTEGER NOT NULL CHECK (mutator_version > 0),
               args_blob BLOB NOT NULL,
               idempotency_root TEXT NOT NULL UNIQUE,
               created_at INTEGER NOT NULL,
               UNIQUE (client_id, client_sequence),
               UNIQUE (client_id, mutation_id))""",
            """CREATE TABLE IF NOT EXISTS store6_mutation_execution(
               client_id TEXT NOT NULL,
               client_sequence INTEGER NOT NULL CHECK (client_sequence > 0),
               phase TEXT NOT NULL,
               current_generation INTEGER NOT NULL CHECK (current_generation >= 0),
               attempt INTEGER NOT NULL CHECK (attempt >= 0),
               last_attempt_at INTEGER,
               active_failure_id INTEGER,
               retired_at INTEGER,
               PRIMARY KEY (client_id, client_sequence),
               CHECK ((attempt = 0 AND last_attempt_at IS NULL)
                 OR (attempt > 0 AND last_attempt_at IS NOT NULL)),
               CHECK (current_generation > 0
                 OR (attempt = 0 AND last_attempt_at IS NULL)),
               CHECK ((phase = 'UNPREPARED' AND current_generation = 0)
                 OR phase = 'PARKED'
                 OR (phase <> 'UNPREPARED' AND current_generation > 0)),
               CHECK ((phase = 'PARKED' AND active_failure_id IS NOT NULL)
                 OR (phase <> 'PARKED' AND active_failure_id IS NULL)),
               CHECK ((phase = 'RETIRED' AND retired_at IS NOT NULL)
                 OR (phase <> 'RETIRED' AND retired_at IS NULL)))""",
            """CREATE TABLE IF NOT EXISTS store6_mutation_attempt(
               client_id TEXT NOT NULL,
               client_sequence INTEGER NOT NULL CHECK (client_sequence > 0),
               generation INTEGER NOT NULL CHECK (generation >= 1),
               effective_namespace TEXT NOT NULL,
               effective_canonical_id TEXT NOT NULL,
               value_codec_version INTEGER NOT NULL CHECK (value_codec_version > 0),
               base_presence TEXT NOT NULL,
               base_blob BLOB,
               mine_presence TEXT NOT NULL,
               mine_blob BLOB,
               precondition_meta_present INTEGER NOT NULL CHECK (precondition_meta_present IN (0, 1)),
               precondition_written_at INTEGER,
               precondition_etag TEXT,
               advertised_retired_through_sequence INTEGER NOT NULL CHECK (advertised_retired_through_sequence >= 0),
               generation_idempotency_key TEXT NOT NULL UNIQUE,
               prepared_at INTEGER NOT NULL,
               conflict_meta_present INTEGER CHECK (conflict_meta_present IN (0, 1)),
               conflict_written_at INTEGER,
               conflict_etag TEXT,
               conflict_received_at INTEGER,
               PRIMARY KEY (client_id, client_sequence, generation),
               CHECK ((base_presence = 'PRESENT') = (base_blob IS NOT NULL)),
               CHECK ((mine_presence = 'PRESENT') = (mine_blob IS NOT NULL)),
               CHECK ((precondition_meta_present = 1 AND precondition_written_at IS NOT NULL)
                 OR (precondition_meta_present = 0 AND precondition_written_at IS NULL
                   AND precondition_etag IS NULL)),
               CHECK ((conflict_meta_present IS NULL AND conflict_written_at IS NULL
                   AND conflict_etag IS NULL AND conflict_received_at IS NULL)
                 OR (conflict_meta_present = 0 AND conflict_written_at IS NULL
                   AND conflict_etag IS NULL AND conflict_received_at IS NOT NULL)
                 OR (conflict_meta_present = 1 AND conflict_written_at IS NOT NULL
                   AND conflict_received_at IS NOT NULL)))""",
            """CREATE TABLE IF NOT EXISTS store6_mutation_ack(
               client_id TEXT NOT NULL,
               client_sequence INTEGER NOT NULL CHECK (client_sequence > 0),
               generation INTEGER NOT NULL CHECK (generation >= 1),
               authoritative_presence TEXT NOT NULL,
               authoritative_blob BLOB,
               value_codec_version INTEGER NOT NULL CHECK (value_codec_version > 0),
               etag TEXT,
               canonical_target_namespace TEXT,
               canonical_target_id TEXT,
               received_at INTEGER NOT NULL,
               PRIMARY KEY (client_id, client_sequence, generation),
               CHECK ((authoritative_presence = 'PRESENT') =
                 (authoritative_blob IS NOT NULL)),
               CHECK ((canonical_target_namespace IS NULL) = (canonical_target_id IS NULL)),
               CHECK (authoritative_presence = 'PRESENT'
                 OR canonical_target_namespace IS NULL))""",
            """CREATE TABLE IF NOT EXISTS store6_mutation_failure(
               failure_id INTEGER PRIMARY KEY,
               client_id TEXT NOT NULL,
               client_sequence INTEGER NOT NULL CHECK (client_sequence > 0),
               generation INTEGER NOT NULL CHECK (generation >= 0),
               kind TEXT NOT NULL,
               detail TEXT NOT NULL CHECK (length(detail) <= 128),
               message TEXT NOT NULL CHECK (length(message) <= 1024),
               occurred_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS store6_mutation_effect(
               client_id TEXT NOT NULL,
               client_sequence INTEGER NOT NULL CHECK (client_sequence > 0),
               effect_index INTEGER NOT NULL CHECK (effect_index >= 0),
               kind TEXT NOT NULL,
               namespace TEXT NOT NULL,
               canonical_id TEXT,
               created_at INTEGER NOT NULL,
               disposition TEXT NOT NULL,
               completed_at INTEGER,
               PRIMARY KEY (client_id, client_sequence, effect_index),
               CHECK ((kind = 'KEY') = (canonical_id IS NOT NULL)),
               CHECK ((disposition = 'PENDING' AND completed_at IS NULL)
                 OR (disposition <> 'PENDING' AND completed_at IS NOT NULL)))""",
            """CREATE TABLE IF NOT EXISTS store6_key_alias(
               source_namespace TEXT NOT NULL,
               source_canonical_id TEXT NOT NULL,
               target_namespace TEXT NOT NULL,
               target_canonical_id TEXT NOT NULL,
               state TEXT NOT NULL,
               created_by_client_id TEXT NOT NULL,
               created_by_sequence INTEGER NOT NULL CHECK (created_by_sequence > 0),
               created_at INTEGER NOT NULL,
               activated_at INTEGER,
               PRIMARY KEY (source_namespace, source_canonical_id),
               CHECK (source_namespace = target_namespace),
               CHECK (source_canonical_id <> target_canonical_id),
               CHECK ((state = 'ACTIVE' AND activated_at IS NOT NULL)
                 OR (state <> 'ACTIVE' AND activated_at IS NULL)))""",
            """CREATE TABLE IF NOT EXISTS store6_key_tombstone(
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
               CHECK ((state = 'SUPERSEDED') = (superseded_at IS NOT NULL)))""",
            """CREATE INDEX IF NOT EXISTS store6_mutation_intent_identity
               ON store6_mutation_intent(namespace, canonical_id, client_id, client_sequence)""",
            """CREATE INDEX IF NOT EXISTS store6_mutation_execution_phase
               ON store6_mutation_execution(client_id, phase, client_sequence)""",
            """CREATE INDEX IF NOT EXISTS store6_mutation_failure_prune
               ON store6_mutation_failure(client_id, client_sequence, failure_id)""",
            """CREATE INDEX IF NOT EXISTS store6_mutation_failure_order
               ON store6_mutation_failure(client_id, failure_id)""",
            """CREATE INDEX IF NOT EXISTS store6_key_tombstone_state
               ON store6_key_tombstone(namespace, canonical_id, state)""",
            """CREATE UNIQUE INDEX IF NOT EXISTS store6_key_tombstone_one_pending
               ON store6_key_tombstone(namespace, canonical_id) WHERE state = 'PENDING'""",
            """CREATE UNIQUE INDEX IF NOT EXISTS store6_key_tombstone_one_active
               ON store6_key_tombstone(namespace, canonical_id) WHERE state = 'ACTIVE'""",
            "INSERT OR IGNORE INTO store6_mutation_schema(id, version) VALUES (0, 2)",
        )
    }

    private fun migrateV1ToV2() {
        if (hasNonQuiescentNamespaceOwner()) {
            error(MIGRATION_QUIESCENCE_DIAGNOSTIC)
        }

        executeAll(
            """CREATE TABLE store6_key_tombstone_v2(
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
               CHECK ((state = 'SUPERSEDED') = (superseded_at IS NOT NULL)))""",
            """INSERT INTO store6_key_tombstone_v2(
               namespace, canonical_id, created_by_client_id, created_by_sequence,
               state, created_at, activated_at, superseded_by_client_id,
               superseded_by_sequence, superseded_at)
               SELECT namespace, canonical_id, created_by_client_id, created_by_sequence,
                 state, created_at, activated_at, superseded_by_client_id,
                 superseded_by_sequence, superseded_at
               FROM store6_key_tombstone""",
            "DROP TABLE store6_key_tombstone",
            "ALTER TABLE store6_key_tombstone_v2 RENAME TO store6_key_tombstone",
            """CREATE INDEX store6_key_tombstone_state
               ON store6_key_tombstone(namespace, canonical_id, state)""",
            """CREATE UNIQUE INDEX store6_key_tombstone_one_pending
               ON store6_key_tombstone(namespace, canonical_id) WHERE state = 'PENDING'""",
            """CREATE UNIQUE INDEX store6_key_tombstone_one_active
               ON store6_key_tombstone(namespace, canonical_id) WHERE state = 'ACTIVE'""",
            "UPDATE store6_mutation_schema SET version = 2 WHERE id = 0",
        )
    }

    private fun mutationSchemaTableExists(): Boolean =
        queryLong(
            """SELECT 1 FROM sqlite_master
               WHERE type = 'table' AND name = 'store6_mutation_schema'
               LIMIT 1""",
        ) != null

    private fun hasNonQuiescentNamespaceOwner(): Boolean =
        queryLong(
            """SELECT 1 FROM store6_mutation_execution
               WHERE phase IN ('INFLIGHT', 'REFRESH_REQUIRED', 'ACKED', 'EFFECTS_PENDING')
                 OR (phase = 'READY' AND attempt > 0)
                 OR (phase = 'READY' AND current_generation > 1)
               LIMIT 1""",
        ) != null

    private fun unsupportedSchemaVersion(version: Long?): Nothing =
        error(
            "mutations-sqldelight found mutation-journal schema version $version in this " +
                "database, but this adapter supports up to $SCHEMA_VERSION. Upgrade the " +
                "mutations-sqldelight dependency for this database, or restore the " +
                "database.",
        )

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

    private fun queryLong(sql: String): Long? =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            },
            0,
            {},
        ).value

    private companion object {
        const val SCHEMA_VERSION = 2L

        const val MIGRATION_QUIESCENCE_DIAGNOSTIC: String =
            "mutations-sqldelight cannot migrate mutation-journal schema version 1 to 2 " +
                "because durable mutation namespaces are not quiescent. Downgrade the " +
                "mutations-sqldelight dependency to a version that supports schema " +
                "version 1, drain or park/retire every non-quiescent mutation namespace, then " +
                "retry the upgrade."
    }
}
