package com.dropbox.android.external.store4

interface DiskWrite<Raw, Key> {
    /**
     * @param key to use to get data from persister.
     *  If data is not available implementer needs to
     *  @return true if data was successfully written otherwise false
     */
    suspend fun write(key: Key, raw: Raw): Boolean
}
