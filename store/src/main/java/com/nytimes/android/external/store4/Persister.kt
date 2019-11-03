package com.nytimes.android.external.store4

/**
 * Interface for fetching data from persister
 * when implementing also think about implementing PathResolver to ease in creating primary keys
 *
 * @param <Raw> data type before parsing
</Raw> */
interface Persister<Raw, Key> : DiskRead<Raw, Key>, DiskWrite<Raw, Key> {

    /**
     * @param key to use to get data from persister
     * If data is not available implementer needs to
     * either return null or throw an exception
     */
    override suspend fun read(key: Key): Raw?

    /**
     * @param key to use to store data to persister
     * @param raw raw string to be stored
     */
    override suspend fun write(key: Key, raw: Raw): Boolean
}
