package com.dropbox.android.external.fs3

/**
 *  Interface for retrieving [Raw] data from disk/persistent sources based on a [key] identifier
 *  @param Raw - the type of data returned from a persistent source
 *  @param Key - a unique identifier for data
 */
interface DiskRead<Raw, Key> {
    /**
     * @param key identifier for data
     * @return data for a [key]
     */
    suspend fun read(key: Key): Raw?
}
