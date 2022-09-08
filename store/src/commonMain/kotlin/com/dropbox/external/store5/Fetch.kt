package com.dropbox.external.store5


import com.dropbox.external.store5.definition.Fetcher
import com.dropbox.external.store5.definition.Updater

typealias GetRequest<Key, Output> = suspend (key: Key) -> Output?
typealias PostRequest<Key, Input, Output> = suspend (key: Key, input: Input) -> Output?
typealias Converter<Input, Output> = (input: Input) -> Output

/**
 * Contains all networking models: [Fetch.Request], [Fetch.Result], [Fetch.OnCompletion].
 */
sealed class Fetch {
    sealed class Request<Key : Any, Output : Any> {
        /**
         * @see [Fetcher]
         */
        data class Get<Key : Any, Input : Any, Output : Any>(
            val get: GetRequest<Key, Output>,
            val post: PostRequest<Key, Input, Output>,
            val converter: Converter<Output, Input>
        ) : Request<Key, Output>()

        /**
         * @see [Updater]
         */
        data class Post<Key : Any, Input : Any, Output : Any>(
            val post: PostRequest<Key, Input, Output>,
            val created: Long,
            val onCompletion: OnCompletion<Output>,
            val converter: Converter<Input, Output>
        ) : Request<Key, Output>()
    }

    sealed class Result<out T : Any> {
        data class Success<T : Any>(val value: T) : Result<T>()
        data class Failure(val error: Throwable) : Result<Nothing>()
    }

    data class OnCompletion<T : Any>(
        val onSuccess: (Result.Success<T>) -> Unit,
        val onFailure: (Result.Failure) -> Unit
    )
}