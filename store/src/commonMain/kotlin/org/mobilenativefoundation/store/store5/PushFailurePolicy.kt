package org.mobilenativefoundation.store.store5

/**
 * Defines the strategy to execute when an eager push to remote fails during a stream request.
 */
enum class PushFailurePolicy {
    /**
     * Stop the stream and emit a [StoreReadResponse.Error] immediately without fetching from network.
     */
    FAIL_FAST,

    /**
     * Suppress remote network fetch and serve only local data, preserving unpushed local changes.
     */
    SKIP_FETCH_KEEP_LOCAL,

    /**
     * Log the error and proceed with the read request, which may fetch remote data (legacy default).
     */
    IGNORE_AND_FETCH,
}
