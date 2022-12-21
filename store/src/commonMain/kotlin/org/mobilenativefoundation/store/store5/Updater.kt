package org.mobilenativefoundation.store.store5

typealias PostRequest<Key, CommonRepresentation> = suspend (key: Key, input: CommonRepresentation) -> UpdaterResult

/**
 * Posts data to remote data source.
 * @see [StoreWriteRequest]
 */
interface Updater<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: CommonRepresentation): UpdaterResult

    /**
     * Executes on network completion.
     */
    val onCompletion: OnUpdaterCompletion<NetworkWriteResponse>?

    companion object {
        fun <Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> by(
            post: PostRequest<Key, CommonRepresentation>,
            onCompletion: OnUpdaterCompletion<NetworkWriteResponse>? = null,
        ): Updater<Key, CommonRepresentation, NetworkWriteResponse> = RealNetworkUpdater(
            post, onCompletion
        )
    }
}

internal class RealNetworkUpdater<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any>(
    private val realPost: PostRequest<Key, CommonRepresentation>,
    override val onCompletion: OnUpdaterCompletion<NetworkWriteResponse>?,
) : Updater<Key, CommonRepresentation, NetworkWriteResponse> {
    override suspend fun post(key: Key, input: CommonRepresentation): UpdaterResult = realPost(key, input)
}
