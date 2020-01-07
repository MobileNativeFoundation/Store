package com.dropbox.android.external.store4

/**
 *  Interface for saving [Raw] data from disk/persistent sources based on a [key] identifier
 *  @param Raw - the type of data to be saved to a persistent source
 *  @param Key - a unique identifier for data
 */
interface DiskWrite<Raw, Key> {
    /**
     * @param key to use to get data from a persistent source.
     *  If data is not available implementer needs to throw an exception
     *  @return true if data was successfully written
     */
    suspend fun write(key: Key, raw: Raw): Boolean
}
