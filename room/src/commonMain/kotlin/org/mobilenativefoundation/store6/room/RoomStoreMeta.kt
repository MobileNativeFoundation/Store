package org.mobilenativefoundation.store6.room

import org.mobilenativefoundation.store6.core.StoreMeta

internal class RoomStoreMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta
