package com.nytimes.android.external.store3.util

@Deprecated("just for testing")
interface KeyParser<in Key, in Raw, out Parsed> {
    suspend fun apply(key: Key, raw: Raw): Parsed
}
