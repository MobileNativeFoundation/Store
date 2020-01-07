package com.dropbox.android.external.store4

interface DiskWrite<Raw, Key> {
    /**
     * @param key to use to get data from a persistent source.
     *  If data is not available implementer needs to throw an exception
     *  @return true if data was successfully written
     */
    suspend fun write(key: Key, raw: Raw): Boolean
}
