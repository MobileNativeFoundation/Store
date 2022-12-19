package org.mobilenativefoundation.store.store5

typealias PostRequest<Key, CommonRepresentation, NetworkWriteResponse> = suspend (key: Key, input: CommonRepresentation) -> UpdaterResult<NetworkWriteResponse>

/**
 * Posts data to remote data source.
 * @see [WriteRequest]
 */
interface Updater<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: CommonRepresentation): UpdaterResult<NetworkWriteResponse>

    /**
     * Executes on network completion.
     */
    val onCompletion: OnUpdaterCompletion<NetworkWriteResponse>?

    companion object {
        fun <Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> by(
            post: PostRequest<Key, CommonRepresentation, NetworkWriteResponse>,
            onCompletion: OnUpdaterCompletion<NetworkWriteResponse>? = null,
        ): Updater<Key, CommonRepresentation, NetworkWriteResponse> = RealNetworkUpdater(
            post, onCompletion
        )
    }
}

internal class RealNetworkUpdater<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any>(
    private val realPost: PostRequest<Key, CommonRepresentation, NetworkWriteResponse>,
    override val onCompletion: OnUpdaterCompletion<NetworkWriteResponse>?,
) : Updater<Key, CommonRepresentation, NetworkWriteResponse> {
    override suspend fun post(key: Key, input: CommonRepresentation): UpdaterResult<NetworkWriteResponse> = realPost(key, input)
}
