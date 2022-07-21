package com.dropbox.kmp.external.fs3

import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

interface DiskAllErase {
    /**
     * @param path to use to delete all files
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun deleteAll(path: String): Boolean
}
