package com.dropbox.store

import kotlinx.datetime.Clock

typealias GetRequest<Key, Output> = suspend (key: Key) -> Output?
typealias PostRequest<Key, Input, Output> = suspend (key: Key, input: Input) -> Output?
typealias Converter<Input, Output> = (input: Input) -> Output

sealed class Fetch {
    sealed class Request<Key : Any, Output : Any> {
        class Get<Key : Any, Input : Any, Output : Any> private constructor(
            val get: GetRequest<Key, Output>,
            val post: PostRequest<Key, Input, Output>,
            val converter: Converter<Output, Input>
        ) : Request<Key, Output>() {
            class Builder<Key : Any, Input : Any, Output : Any> {
                private lateinit var get: GetRequest<Key, Output>
                private lateinit var post: PostRequest<Key, Input, Output>
                private lateinit var converter: Converter<Output, Input>
                fun get(get: GetRequest<Key, Output>) = apply { this.get = get }
                fun post(post: PostRequest<Key, Input, Output>) = apply { this.post = post }
                fun converter(converter: Converter<Output, Input>) = apply { this.converter = converter }
                fun build() = Get(get = this.get, post = this.post, converter = this.converter)
            }
        }

        class Post<Key : Any, Input : Any, Output : Any> private constructor(
            val post: PostRequest<Key, Input, Output>,
            val created: Long,
            val onCompletion: OnCompletion<Output>,
            val converter: Converter<Input, Output>
        ) : Request<Key, Output>() {
            class Builder<Key : Any, Input : Any, Output : Any> {
                private lateinit var post: PostRequest<Key, Input, Output>
                private lateinit var onCompletion: OnCompletion<Output>
                private lateinit var converter: Converter<Input, Output>
                fun post(post: PostRequest<Key, Input, Output>) = apply { this.post = post }
                fun onCompletion(onCompletion: OnCompletion<Output>) = apply { this.onCompletion = onCompletion }
                fun converter(converter: Converter<Input, Output>) = apply { this.converter = converter }
                fun build() = Post(
                    post = this.post,
                    created = Clock.System.now().epochSeconds,
                    onCompletion = this.onCompletion,
                    converter = this.converter
                )
            }
        }
    }

    sealed class Result<out T : Any> {
        data class Success<T : Any>(val value: T) : Result<T>()

        data class Failure(val error: Throwable) : Result<Nothing>()
    }

    class OnCompletion<T : Any> private constructor(
        val onSuccess: (Result.Success<T>) -> Unit,
        val onFailure: (Result.Failure) -> Unit
    ) {
        class Builder<T : Any> {
            private var onSuccess: (Result.Success<T>) -> Unit = {}
            private var onFailure: (Result.Failure) -> Unit = {}

            fun onSuccess(onSuccess: (Result.Success<T>) -> Unit) = apply { this.onSuccess = onSuccess }
            fun onFailure(onFailure: (Result.Failure) -> Unit) = apply { this.onFailure = onFailure }
            fun build() = OnCompletion(
                onSuccess = this.onSuccess,
                onFailure = this.onFailure
            )
        }
    }
}