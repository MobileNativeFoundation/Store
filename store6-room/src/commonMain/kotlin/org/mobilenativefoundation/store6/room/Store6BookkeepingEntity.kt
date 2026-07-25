package org.mobilenativefoundation.store6.room

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Adapter-owned TD-6 bookkeeping sidecar.
 *
 * Include this entity and [Store6WatermarkEntity] in the user's Room database, add an abstract
 * [Store6BookkeeperDao] accessor, and migrate existing databases with
 * [Store6RoomSchema.createTables]. This sidecar requires no changes to user tables.
 *
 * This surface is a seam freeze candidate pending Matt's signature.
 */
@ExperimentalStoreApi
@Entity(
    tableName = "store6_bookkeeping",
    primaryKeys = ["namespace", "canonical_id"],
)
public class Store6BookkeepingEntity(
    @ColumnInfo(name = "namespace")
    public val namespace: String,
    @ColumnInfo(name = "canonical_id")
    public val canonicalId: String,
    @ColumnInfo(name = "written_at_epoch_millis")
    public val writtenAtEpochMillis: Long?,
    @ColumnInfo(name = "etag")
    public val etag: String?,
    @ColumnInfo(name = "last_success_sequence")
    public val lastSuccessSequence: Long?,
    @ColumnInfo(name = "last_failure_at_epoch_millis")
    public val lastFailureAtEpochMillis: Long?,
    @ColumnInfo(name = "consecutive_failures")
    public val consecutiveFailures: Int,
    @ColumnInfo(name = "stale_sequence")
    public val staleSequence: Long?,
)
