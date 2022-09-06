@file:Suppress("UNCHECKED_CAST")

package com.dropbox.store

import com.dropbox.store.impl.ShareableMarket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

interface Market<Key : Any> {
    suspend fun <Input : Any, Output : Any> read(request: Request.Read<Key, Input, Output>): MutableSharedFlow<Response<Output>>
    suspend fun <Input : Any, Output : Any> write(request: Request.Write<Key, Input, Output>): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun clear(): Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> of(
            coroutineScope: CoroutineScope,
            stores: List<Store<Key, Input, Output>>,
            conflictResolution: ConflictResolution<Key, Input, Output>
        ) = ShareableMarket(coroutineScope, stores, conflictResolution)
    }

    sealed class Request<Key> {
        abstract val key: Key

        class Write<Key : Any, Input : Any, Output : Any> constructor(
            override val key: Key,
            val input: Input,
            val request: Fetch.Request.Post<Key, Input, Output>,
            val serializer: KSerializer<Input>,
            val onCompletions: List<OnCompletion<Output>>
        ) : Request<Key>() {
            class OnCompletion<T : Any> private constructor(
                val onSuccess: (Response.Success<T>) -> Unit,
                val onFailure: (Response.Failure) -> Unit
            ) {
                class Builder<T : Any> {
                    private var onSuccess: (Response.Success<T>) -> Unit = {}
                    private var onFailure: (Response.Failure) -> Unit = {}

                    fun onSuccess(onSuccess: (Response.Success<T>) -> Unit) = apply { this.onSuccess = onSuccess }
                    fun onFailure(onFailure: (Response.Failure) -> Unit) = apply { this.onFailure = onFailure }
                    fun build() = OnCompletion(onSuccess, onFailure)
                }
            }

            @OptIn(InternalSerializationApi::class)
            class Builder<Key : Any, Input : Any, Output : Any>(
                val key: Key,
                val input: Input
            ) {
                lateinit var request: Fetch.Request.Post<Key, Input, Output>
                var onCompletions: MutableList<OnCompletion<Output>> = mutableListOf()
                fun request(request: Fetch.Request.Post<Key, Input, Output>) = apply { this.request = request }
                fun onCompletion(onCompletion: OnCompletion<Output>) = apply { this.onCompletions.add(onCompletion) }

                inline fun <reified T : Input> build() = Write(
                    key = key,
                    input = input,
                    request = request,
                    serializer = T::class.serializer() as KSerializer<Input>,
                    onCompletions = onCompletions
                )
            }
        }

        class Read<Key : Any, Input : Any, Output : Any> constructor(
            override val key: Key,
            val request: Fetch.Request.Get<Key, Input, Output>,
            val serializer: KSerializer<Output>,
            val onCompletions: List<OnCompletion<Output>>,
            val refresh: Boolean = false
        ) : Request<Key>() {
            class OnCompletion<T : Any> private constructor(
                val onSuccess: (Response.Success<T>) -> Unit,
                val onFailure: (Response.Failure) -> Unit
            ) {
                class Builder<T : Any> {
                    private var onSuccess: (Response.Success<T>) -> Unit = {}
                    private var onFailure: (Response.Failure) -> Unit = {}

                    fun onSuccess(onSuccess: (Response.Success<T>) -> Unit) = apply { this.onSuccess = onSuccess }
                    fun onFailure(onFailure: (Response.Failure) -> Unit) = apply { this.onFailure = onFailure }
                    fun build() = OnCompletion<T>(onSuccess, onFailure)
                }
            }

            @OptIn(InternalSerializationApi::class)
            class Builder<Key : Any, Input : Any, Output : Any>(
                val key: Key,
                val refresh: Boolean = false
            ) {
                lateinit var request: Fetch.Request.Get<Key, Input, Output>
                var onCompletions: MutableList<OnCompletion<Output>> = mutableListOf()

                fun request(request: Fetch.Request.Get<Key, Input, Output>) = apply { this.request = request }
                fun onCompletion(onCompletion: OnCompletion<Output>) = apply { this.onCompletions.add(onCompletion) }

                inline fun <reified T : Output> build() = Read(
                    key = key,
                    request = request,
                    serializer = T::class.serializer() as KSerializer<Output>,
                    refresh = refresh,
                    onCompletions = onCompletions
                )
            }
        }
    }

    sealed class Response<out Output> {
        object Loading : Response<Nothing>()
        data class Success<Output>(val value: Output, val origin: Origin) : Response<Output>()
        data class Failure(val error: Throwable, val origin: Origin) : Response<Nothing>()
        object Empty : Response<Nothing>()

        companion object {
            enum class Origin {
                Store,
                Remote,
                LocalWrite
            }
        }
    }
}