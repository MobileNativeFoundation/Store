package org.mobilenativefoundation.store.store5.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.NetworkFetcher
import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.data.model.MarketData
import org.mobilenativefoundation.store.store5.data.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteNetworkWriteResponse
import org.mobilenativefoundation.store.store5.data.model.NoteStoreDatabaseRepresentation
import org.mobilenativefoundation.store.store5.impl.MemoryLruStore

internal object FakeComplexMarket {

    object Success {

        class MemoryLruStoreWrapper : Store<NoteMarketKey, NoteCommonRepresentation, NoteCommonRepresentation> {
            private val store = MemoryLruStore<NoteCommonRepresentation>(10)

            override fun read(key: NoteMarketKey): Flow<NoteCommonRepresentation?> = channelFlow {
                if (key is NoteMarketKey.Read) {
                    store.read(key.toString()).collect { send(it) }
                } else {
                    send(null)
                }
            }

            override suspend fun write(key: NoteMarketKey, input: NoteCommonRepresentation): Boolean {
                return when (key) {
                    is NoteMarketKey.Read.GetById -> when (input.data) {
                        is MarketData.Collection -> {
                            input.data.items.forEach {
                                store.write(key.toString(), NoteCommonRepresentation(MarketData.Single(it)))
                            }
                            true
                        }

                        is MarketData.Single -> {
                            store.write(key.toString(), input)
                            true
                        }

                        null -> {
                            false
                        }
                    }

                    is NoteMarketKey.Read.Paginate -> {
                        when (input.data) {
                            is MarketData.Collection -> {
                                input.data.items.forEach {
                                    store.write(key.toString(), NoteCommonRepresentation(MarketData.Single(it)))
                                }
                                true
                            }

                            is MarketData.Single -> {
                                store.write(key.toString(), input)
                                true
                            }

                            null -> {
                                false
                            }
                        }
                    }

                    is NoteMarketKey.Write -> {
                        when (input.data) {
                            is MarketData.Collection -> {
                                input.data.items.forEach {
                                    store.write(key.toString(), NoteCommonRepresentation(MarketData.Single(it)))
                                }
                                true
                            }

                            is MarketData.Single -> {
                                store.write(key.toString(), input)
                                true
                            }

                            null -> {
                                false
                            }
                        }
                    }
                }
            }

            override suspend fun delete(key: NoteMarketKey): Boolean = if (key is NoteMarketKey.Read) {
                store.delete(key.toString())
            } else {
                false
            }

            override suspend fun clear(): Boolean = store.clear()
            override val converter: Store.Converter<NoteCommonRepresentation, NoteCommonRepresentation>? = null
        }

        val memoryLruStore = MemoryLruStoreWrapper()
        val database = FakeComplexDatabase()
        val api = FakeComplexApi()

        val bookkeeper = bookkeeper(database)
        val fetcher = fetcher(api)
        fun updater(
            onCompletion: OnNetworkCompletion<NoteNetworkWriteResponse> = OnNetworkCompletion(
                onSuccess = {},
                onFailure = {}
            )
        ) = updater(api = api, fail = false, onCompletion = onCompletion)
    }

    object Failure {
        val database = FakeComplexDatabase()
        val api = FakeComplexApi()

        val memoryLruCacheStore = Store.by<NoteMarketKey, NoteCommonRepresentation, NoteCommonRepresentation>(
            reader = { throw Exception() },
            writer = { _, _ -> throw Exception() },
            deleter = { throw Exception() },
            clearer = { throw Exception() },
        )

        val databaseStore = Store.by<NoteMarketKey, NoteStoreDatabaseRepresentation, NoteCommonRepresentation>(
            reader = { throw Exception() },
            writer = { _, _ -> throw Exception() },
            deleter = { throw Exception() },
            clearer = { throw Exception() },
        )

        val bookkeeper = bookkeeper(database)
        val fetcher = fetcher(api, fail = true)
        fun updater(
            onCompletion: OnNetworkCompletion<NoteNetworkWriteResponse> = OnNetworkCompletion(
                onSuccess = {},
                onFailure = {}
            )
        ) = updater(api = api, fail = true, onCompletion = onCompletion)
    }

    private fun bookkeeper(database: FakeComplexDatabase) = Bookkeeper.by<NoteMarketKey>(
        read = { key -> database.getLastWriteTime(key.toString()) },
        write = { key, timestamp -> database.setLastWriteTime(key.toString(), timestamp) },
        delete = { key -> database.deleteWriteRequest(key.toString()) },
        deleteAll = { database.deleteAllWriteRequests() }
    )

    private fun updater(
        api: FakeComplexApi,
        fail: Boolean = false,
        onCompletion: OnNetworkCompletion<NoteNetworkWriteResponse> = OnNetworkCompletion(
            onSuccess = {},
            onFailure = {}
        )
    ) =
        NetworkUpdater.by<NoteMarketKey, NoteCommonRepresentation, NoteNetworkWriteResponse>(
            post = { key, input -> api.post(key, input, fail) },
            converter = {
                when (it.data) {
                    is MarketData.Collection -> NoteNetworkWriteResponse("${it.data.items[0].id}-${it.data.items.last().id}", true)
                    is MarketData.Single -> NoteNetworkWriteResponse(it.data.item.id, true)
                    null -> NoteNetworkWriteResponse(null, false)
                }
            },
            responseValidator = { it.ok },
            onCompletion = onCompletion
        )

    private fun fetcher(api: FakeComplexApi, fail: Boolean = false) =
        NetworkFetcher.by(
            get = { key -> api.get(key, fail) },
            converter = { NoteCommonRepresentation(it.data) },
            updater = updater(api, fail)
        )
}
