package com.nytimes.android.external.store4

interface DiskRead<Raw, Key> {
    suspend fun read(key: Key): Raw?
}
