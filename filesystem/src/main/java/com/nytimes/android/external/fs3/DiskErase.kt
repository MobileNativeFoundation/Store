package com.nytimes.android.external.fs3


interface DiskErase<Raw, Key> {
    /**
     * @param key to use to delete a particular file using persister
     */
    suspend fun delete(key: Key): Boolean
}
