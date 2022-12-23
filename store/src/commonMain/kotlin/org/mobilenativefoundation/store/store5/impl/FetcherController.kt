/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store.multicast5.Multicaster
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.FetcherResult
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

/**
 * This class maintains one and only 1 fetcher for a given [Key].
 *
 * Any value emitted by the fetcher is sent into the [sourceOfTruth] before it is dispatched.
 * If [sourceOfTruth] is `null`, [enablePiggyback] is set to true by default so that previous
 * fetcher requests receives values dispatched by later requests even if they don't share the
 * request.
 */
internal class FetcherController<Key : Any, Network : Any, Common : Any, SOT : Any>(
    /**
     * The [CoroutineScope] to use when collecting from the fetcher
     */
    private val scope: CoroutineScope,
    /**
     * The function that provides the actualy fetcher flow when needed
     */
    private val realFetcher: Fetcher<Key, Network>,
    /**
     * [SourceOfTruth] to send the data each time fetcher dispatches a value. Can be `null` if
     * no [SourceOfTruth] is available.
     */
    private val sourceOfTruth: SourceOfTruthWithBarrier<Key, Network, Common, SOT>?,

    private val converter: Converter<Network, Common, SOT>? = null
) {
    @Suppress("USELESS_CAST", "UNCHECKED_CAST") // needed for multicaster source
    private val fetchers = RefCountedResource(
        create = { key: Key ->
            Multicaster(
                scope = scope,
                bufferSize = 0,
                source = flow { emitAll(realFetcher(key)) }.map {
                    when (it) {
                        is FetcherResult.Data -> {
                            StoreReadResponse.Data(
                                it.value,
                                origin = StoreReadResponseOrigin.Fetcher
                            ) as StoreReadResponse<Network>
                        }

                        is FetcherResult.Error.Message -> StoreReadResponse.Error.Message(
                            it.message,
                            origin = StoreReadResponseOrigin.Fetcher
                        )

                        is FetcherResult.Error.Exception -> StoreReadResponse.Error.Exception(
                            it.error,
                            origin = StoreReadResponseOrigin.Fetcher
                        )
                    }
                }.onEmpty {
                    emit(StoreReadResponse.NoNewData(StoreReadResponseOrigin.Fetcher))
                },
                /**
                 * When enabled, downstream collectors are never closed, instead, they are kept active to
                 * receive values dispatched by fetchers created after them. This makes [FetcherController]
                 * act like a [SourceOfTruth] in the lack of a [SourceOfTruth] provided by the developer.
                 */
                piggybackingDownstream = true,
                onEach = { response ->
                    response.dataOrNull()?.let { network ->
                        val common = converter?.fromNetworkToCommon(network)
                        val input = common ?: network
                        sourceOfTruth?.write(key, input as Common)
                    }
                }
            )
        },
        onRelease = { _: Key, multicaster: Multicaster<StoreReadResponse<Network>> ->
            multicaster.close()
        }
    )

    fun getFetcher(key: Key, piggybackOnly: Boolean = false): Flow<StoreReadResponse<Network>> {
        return flow {
            val fetcher = acquireFetcher(key)
            try {
                emitAll(fetcher.newDownstream(piggybackOnly))
            } finally {
                withContext(NonCancellable) {
                    fetchers.release(key, fetcher)
                }
            }
        }
    }

    /**
     * This functions goes to great length to prevent capturing the calling context from
     * [getFetcher]. The reason being that the [Flow] returned by [getFetcher] is collected on the
     * user's context and [acquireFetcher] will, optionally, launch a long running coroutine on the
     * [FetcherController]'s [scope]. In order to avoid capturing a reference to the scope we need
     * to:
     * 1) Not inline this function as that will cause the lambda to capture a reference to the
     * surrounding suspend lambda which, in turn, holds a reference to the user's coroutine context.
     * 2) Use [async]-[await] instead of
     * [kotlinx.coroutines.withContext] as [kotlinx.coroutines.withContext] will also hold onto a
     * reference to the caller's context (the LHS parameter of the new context which is used to run
     * the operation).
     */
    private suspend fun acquireFetcher(key: Key) = scope.async {
        fetchers.acquire(key)
    }.await()

    // visible for testing
    internal suspend fun fetcherSize() = fetchers.size()
}
