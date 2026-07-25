package org.mobilenativefoundation.store6.room

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Adapter-owned TD-6 watermark row.
 *
 * See [Store6BookkeepingEntity] for the required database inclusion and migration rules.
 */
@ExperimentalStoreApi
@Entity(tableName = "store6_watermarks")
public class Store6WatermarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope")
    public val scope: String,
    @ColumnInfo(name = "sequence")
    public val sequence: Long,
)
