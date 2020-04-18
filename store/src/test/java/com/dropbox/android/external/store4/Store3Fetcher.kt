package com.dropbox.android.external.store4

/**
 * Interface for fetching new data for a Store
 *
 * @param <Raw> data type before parsing
</Raw> */
@Deprecated("used in tests")
// TODO cleanup
interface Store3Fetcher<Raw, Key> {

    /**
     * @param key Container with Key and Type used as a request param
     * @return Observable that emits [Raw] data
     */
    suspend fun invoke(key: Key): Raw
}
