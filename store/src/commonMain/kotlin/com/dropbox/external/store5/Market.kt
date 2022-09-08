package com.dropbox.external.store5

import com.dropbox.external.store5.definition.MarketReader
import com.dropbox.external.store5.definition.MarketWriter
import com.dropbox.external.store5.definition.Fetcher
import com.dropbox.external.store5.definition.Updater
import com.dropbox.external.store5.impl.ShareableMarket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.KSerializer


/**
 * Integrates stores and a conflict resolution system.
 * @see [ShareableMarket]
 */
interface Market<Key : Any> {
    suspend fun <Input : Any, Output : Any> read(request: Request.Reader<Key, Input, Output>): MutableSharedFlow<Response<Output>>
    suspend fun <Input : Any, Output : Any> write(request: Request.Writer<Key, Input, Output>): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun delete(): Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> of(
            coroutineScope: CoroutineScope,
            stores: List<Store<Key, Input, Output>>,
            conflictResolver: ConflictResolver<Key, Input, Output>
        ) = ShareableMarket(coroutineScope, stores, conflictResolver)
    }

    sealed class Request<Key> {
        abstract val key: Key

        /**
         * @see [MarketWriter]
         */
        data class Writer<Key : Any, Input : Any, Output : Any>(
            override val key: Key,
            val input: Input,
            val request: Updater<Key, Input, Output>,
            val serializer: KSerializer<Input>,
            val onCompletions: List<OnCompletion<Output>>
        ) : Request<Key>() {
            data class OnCompletion<T : Any>(
                val onSuccess: (Response.Success<T>) -> Unit = {},
                val onFailure: (Response.Failure) -> Unit = {}
            )
        }

        /**
         * @see [MarketReader]
         */
        data class Reader<Key : Any, Input : Any, Output : Any>(
            override val key: Key,
            val request: Fetcher<Key, Input, Output>,
            val serializer: KSerializer<Output>,
            val onCompletions: List<OnCompletion<Output>>,
            val refresh: Boolean = false
        ) : Request<Key>() {
            data class OnCompletion<T : Any>(
                val onSuccess: (Response.Success<T>) -> Unit,
                val onFailure: (Response.Failure) -> Unit
            )
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