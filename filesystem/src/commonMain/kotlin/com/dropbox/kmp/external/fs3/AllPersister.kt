package com.dropbox.kmp.external.fs3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import okio.FileNotFoundException

interface AllPersister<Raw, Key> : Persister<Raw, Key>, DiskAllRead<Raw>, DiskAllErase {
    /**
     * @param path to use to get data from persister
     * If data is not available implementer needs to
     * throw an exception
     */
    @Throws(FileNotFoundException::class)
    override fun CoroutineScope.readAll(path: String): ReceiveChannel<Raw>

    /**
     * @param path to delete all the data in the the path.
     */
    override suspend fun deleteAll(path: String): Boolean

    /**
     * @param key to use to get data from persister
     * If data is not available implementer needs to
     * throw an exception
     */
    override suspend fun read(key: Key): Raw?

    /**
     * @param key to use to store data to persister
     * @param raw raw string to be stored
     */
    override suspend fun write(key: Key, raw: Raw): Boolean
}
