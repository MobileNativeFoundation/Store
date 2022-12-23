package org.mobilenativefoundation.store.store5

typealias PostRequest<Key, Output> = suspend (key: Key, value: Output) -> UpdaterResult

/**
 * Posts data to remote data source.
 * @see [StoreWriteRequest]
 */
interface Updater<Key : Any, Output : Any, Response : Any> {
    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, value: Output): UpdaterResult

    /**
     * Executes on network completion.
     */
    val onCompletion: OnUpdaterCompletion<Response>?

    companion object {
        fun <Key : Any, Output : Any, Response : Any> by(
            post: PostRequest<Key, Output>,
            onCompletion: OnUpdaterCompletion<Response>? = null,
        ): Updater<Key, Output, Response> = RealNetworkUpdater(
            post, onCompletion
        )
    }
}

internal class RealNetworkUpdater<Key : Any, Output : Any, Response : Any>(
    private val realPost: PostRequest<Key, Output>,
    override val onCompletion: OnUpdaterCompletion<Response>?,
) : Updater<Key, Output, Response> {
    override suspend fun post(key: Key, value: Output): UpdaterResult = realPost(key, value)
}
