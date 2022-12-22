package org.mobilenativefoundation.store.store5

typealias PostRequest<Key, Common> = suspend (key: Key, input: Common) -> UpdaterResult

/**
 * Posts data to remote data source.
 * @see [StoreWriteRequest]
 */
interface Updater<Key : Any, Common : Any, Response : Any> {
    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: Common): UpdaterResult

    /**
     * Executes on network completion.
     */
    val onCompletion: OnUpdaterCompletion<Response>?

    companion object {
        fun <Key : Any, Common : Any, Response : Any> by(
            post: PostRequest<Key, Common>,
            onCompletion: OnUpdaterCompletion<Response>? = null,
        ): Updater<Key, Common, Response> = RealNetworkUpdater(
            post, onCompletion
        )
    }
}

internal class RealNetworkUpdater<Key : Any, Common : Any, Response : Any>(
    private val realPost: PostRequest<Key, Common>,
    override val onCompletion: OnUpdaterCompletion<Response>?,
) : Updater<Key, Common, Response> {
    override suspend fun post(key: Key, input: Common): UpdaterResult = realPost(key, input)
}
