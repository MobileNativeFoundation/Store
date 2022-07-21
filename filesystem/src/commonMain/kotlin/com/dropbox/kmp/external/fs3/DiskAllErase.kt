package com.dropbox.android.external.fs3

interface DiskAllErase {
    /**
     * @param path to use to delete all files
     */
    suspend fun deleteAll(path: String): Boolean
}
