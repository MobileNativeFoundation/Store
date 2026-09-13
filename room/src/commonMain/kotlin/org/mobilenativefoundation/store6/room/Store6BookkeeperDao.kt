package org.mobilenativefoundation.store6.room

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** Room primitives for the adapter-owned bookkeeping sidecar. */
@ExperimentalStoreApi
@Dao
public interface Store6BookkeeperDao {
    @Query(
        "SELECT * FROM store6_bookkeeping " +
            "WHERE namespace = :namespace AND canonical_id = :canonicalId",
    )
    public suspend fun record(
        namespace: String,
        canonicalId: String,
    ): Store6BookkeepingEntity?

    @Upsert
    public suspend fun upsertRecord(record: Store6BookkeepingEntity)

    @Query(
        "DELETE FROM store6_bookkeeping " +
            "WHERE namespace = :namespace AND canonical_id = :canonicalId",
    )
    public suspend fun deleteRecord(
        namespace: String,
        canonicalId: String,
    )

    @Query("DELETE FROM store6_bookkeeping WHERE namespace = :namespace")
    public suspend fun deleteNamespaceRecords(namespace: String)

    @Query("DELETE FROM store6_bookkeeping")
    public suspend fun deleteAllRecords()

    @Query("SELECT sequence FROM store6_watermarks WHERE scope = :scope")
    public suspend fun watermark(scope: String): Long?

    @Upsert
    public suspend fun upsertWatermark(watermark: Store6WatermarkEntity)
}
