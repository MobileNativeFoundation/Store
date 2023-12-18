package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store.store5.Fetcher.Companion.of
import org.mobilenativefoundation.store.store5.Fetcher.Companion.ofFlow
import org.mobilenativefoundation.store.store5.Fetcher.Companion.ofResult

/**
 * Fetcher is used by [Store] to fetch network records for a given key. The return type is [Flow] to
 * allow for multiple result per request.
 *
 * Note: Store does not catch exceptions thrown by a [Fetcher]. This is done in order to avoid
 * silently swallowing NPEs and such. Use [FetcherResult.Error] to communicate expected errors.
 *
 * See [ofResult] for easily translating from a regular `suspend` function.
 * See [ofFlow], [of] for easily translating to [FetcherResult] (and
 * automatically transforming exceptions into [FetcherResult.Error]).
 *
 * @property name Unique name to enable differentiation when [fallback] exists.
 */
interface Fetcher<Key : Any, Network : Any> {
    val name: String?

    val fallback: Fetcher<Key, Network>?

    /**
     * Returns a flow of the item represented by the given [key].
     */
    operator fun invoke(key: Key): Flow<FetcherResult<Network>>

    companion object {
        /**
         * "Creates" a [Fetcher] from a [flowFactory].
         *
         * Use when creating a [Store] that fetches objects in a multiple responses per request
         * network protocol (e.g., Web Sockets).
         *
         * [Store] does not catch exception thrown in [flowFactory] or in the returned [Flow]. These
         * exception will be propagated to the caller.
         *
         * @param flowFactory a factory for a [Flow]ing source of network records.
         */
        fun <Key : Any, Network : Any> ofResultFlow(
            flowFactory: (Key) -> Flow<FetcherResult<Network>>
        ): Fetcher<Key, Network> = FactoryFetcher(factory = flowFactory)

        /**
         * Creates a [Fetcher] with a [fallback] from a [flowFactory].
         * Use instead of [ofResultFlow] if implementing fallback mechanisms.
         * @param name Unique name to enable differentiation of fetchers.
         */
        fun <Key : Any, Network : Any> ofResultFlowWithFallback(
            name: String,
            flowFactory: (Key) -> Flow<FetcherResult<Network>>,
            fallback: Fetcher<Key, Network>
        ): Fetcher<Key, Network> = FactoryFetcherWithFallback(name = name, factory = flowFactory, fallback = fallback)

        /**
         * "Creates" a [Fetcher] from a non-[Flow] source.
         *
         * Use when creating a [Store] that fetches objects in a single response per request network
         * protocol (e.g., Http).
         *
         * [Store] does not catch exception thrown in [fetch]. These exception will be propagated to the
         * caller.
         *
         * @param fetch a source of network records.
         */
        fun <Key : Any, Network : Any> ofResult(
            fetch: suspend (Key) -> FetcherResult<Network>
        ): Fetcher<Key, Network> = ofResultFlow(fetch.asFlow())

        /**
         * Creates a [Fetcher] with a [fallback] from a non-Flow source.
         * Use instead of [ofResult] if implementing fallback mechanisms.
         * @param name Unique name to enable differentiation of fetchers.
         */
        fun <Key : Any, Network : Any> ofResultWithFallback(
            name: String,
            fetch: suspend (Key) -> FetcherResult<Network>,
            fallback: Fetcher<Key, Network>
        ): Fetcher<Key, Network> = ofResultFlowWithFallback(name, fetch.asFlow(), fallback)

        /**
         * "Creates" a [Fetcher] from a [flowFactory] and translate the results to a [FetcherResult].
         *
         * Emitted values will be wrapped in [FetcherResult.Data]. if an exception disrupts the flow then
         * it will be wrapped in [FetcherResult.Error]. Exceptions thrown in [flowFactory] itself are not
         * caught and will be returned to the caller.
         *
         * Use when creating a [Store] that fetches objects in a multiple responses per request
         * network protocol (e.g Web Sockets).
         *
         * @param flowFactory a factory for a [Flow]ing source of network records.
         */
        fun <Key : Any, Network : Any> ofFlow(
            name: String? = null,
            flowFactory: (Key) -> Flow<Network>
        ): Fetcher<Key, Network> = FactoryFetcher { key: Key ->
            flowFactory(key)
                .map<Network, FetcherResult<Network>> { FetcherResult.Data(it, name) }
                .catch { throwable: Throwable -> emit(FetcherResult.Error(throwable)) }
        }

        /**
         * Creates a [Fetcher] with a [fallback] from a [flowFactory].
         * Use instead of [ofFlow] if implementing fallback mechanisms.
         * @param name Unique name to enable differentiation of fetchers
         */
        fun <Key : Any, Network : Any> ofFlowWithFallback(
            name: String,
            fallback: Fetcher<Key, Network>,
            flowFactory: (Key) -> Flow<Network>,
        ): Fetcher<Key, Network> = FactoryFetcherWithFallback(name = name, factory = { key: Key ->
            flowFactory(key)
                .map<Network, FetcherResult<Network>> {
                    FetcherResult.Data(it, name)
                }
                .catch { throwable: Throwable -> emit(FetcherResult.Error(throwable)) }
        }, fallback = fallback)

        /**
         * "Creates" a [Fetcher] from a non-[Flow] source and translate the results to a [FetcherResult].
         *
         * Emitted values will be wrapped in [FetcherResult.Data]. if an exception disrupts the flow then
         * it will be wrapped in [FetcherResult.Error]
         *
         * Use when creating a [Store] that fetches objects in a single response per request
         * network protocol (e.g Http).
         *
         * @param fetch a source of network records.
         */
        fun <Key : Any, Network : Any> of(
            name: String? = null,
            fetch: suspend (key: Key) -> Network
        ): Fetcher<Key, Network> =
            ofFlow(name, fetch.asFlow())

        /**
         * Creates a [Fetcher] with a [fallback] from a non-Flow source.
         * Use instead of [of] if implementing fallback mechanisms.
         * @param name Unique name to enable differentiation of fetchers
         */
        fun <Key : Any, Network : Any> withFallback(
            name: String,
            fallback: Fetcher<Key, Network>,
            fetch: suspend (key: Key) -> Network
        ): Fetcher<Key, Network> =
            ofFlowWithFallback(name, fallback, fetch.asFlow())

        private fun <Key : Any, Network : Any> (suspend (key: Key) -> Network).asFlow() = { key: Key ->
            flow {
                emit(invoke(key))
            }
        }

        private class FactoryFetcher<Key : Any, Network : Any>(
            private val factory: (Key) -> Flow<FetcherResult<Network>>,
        ) : Fetcher<Key, Network> {
            override val name: String? = null
            override val fallback: Fetcher<Key, Network>? = null
            override fun invoke(key: Key): Flow<FetcherResult<Network>> = factory(key)
        }

        private fun <Key : Any, Network : Any> tryFetch(
            key: Key,
            factory: (Key) -> Flow<FetcherResult<Network>>,
            fallback: Fetcher<Key, Network>?
        ): Flow<FetcherResult<Network>> = channelFlow {
            factory(key).collect { fetcherResult ->
                when (fetcherResult) {
                    is FetcherResult.Data -> {
                        send(fetcherResult)
                    }

                    is FetcherResult.Error<*> -> {
                        if (fallback != null) {
                            tryFetch(key, fallback::invoke, fallback.fallback).collect { send(it) }
                        } else {
                            send(fetcherResult)
                        }
                    }
                }
            }
        }

        private class FactoryFetcherWithFallback<Key : Any, Network : Any>(
            override val name: String,
            private val factory: (Key) -> Flow<FetcherResult<Network>>,
            override val fallback: Fetcher<Key, Network>,
        ) : Fetcher<Key, Network> {
            override fun invoke(key: Key): Flow<FetcherResult<Network>> = tryFetch(key, factory, fallback)
        }
    }
}
