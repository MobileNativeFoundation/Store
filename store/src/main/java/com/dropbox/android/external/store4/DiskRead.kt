package com.dropbox.android.external.store4

/**
 *
 */
interface DiskRead<Raw, Key> {
    /**
     * @param key identifier for data
     * @return data for a [key]
     */
    suspend fun read(key: Key): Raw?
}
