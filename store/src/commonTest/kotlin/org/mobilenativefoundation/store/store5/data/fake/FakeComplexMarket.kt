package org.mobilenativefoundation.store.store5.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.NetworkFetcher
import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.data.model.MarketData
import org.mobilenativefoundation.store.store5.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput
import org.mobilenativefoundation.store.store5.impl.MemoryLruStore

internal object FakeComplexMarket {
    fun NoteMarketInput.convert(): NoteMarketOutput = when (this.data) {
        is MarketData.Collection -> NoteMarketOutput.Read(this.data)
        is MarketData.Single -> NoteMarketOutput.Read(this.data)
        null -> NoteMarketOutput.Read(null)
    }

    object Success {

        class MemoryLruStoreWrapper : Store<NoteMarketKey, NoteMarketInput, NoteMarketOutput> {
            private val store = MemoryLruStore<NoteMarketOutput>(10)

            override fun read(key: NoteMarketKey): Flow<NoteMarketOutput?> = channelFlow {
                if (key is NoteMarketKey.Read) {
                    store.read(key.toString()).collect { send(it) }
                } else {
                    send(null)
                }
            }

            override suspend fun write(key: NoteMarketKey, input: NoteMarketInput): Boolean {
                return when (key) {
                    is NoteMarketKey.Read.GetById -> when (input.data) {
                        is MarketData.Collection -> {
                            input.data.items.forEach {
                                store.write(key.toString(), NoteMarketOutput.Read(MarketData.Single(it)))
                            }
                            true
                        }

                        is MarketData.Single -> {
                            store.write(key.toString(), input.convert())
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
                                    store.write(key.toString(), NoteMarketOutput.Read(MarketData.Single(it)))
                                }
                                true
                            }

                            is MarketData.Single -> {
                                store.write(key.toString(), input.convert())
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
                                    store.write(key.toString(), NoteMarketOutput.Read(MarketData.Single(it)))
                                }
                                true
                            }

                            is MarketData.Single -> {
                                store.write(key.toString(), input.convert())
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
        }

        val memoryLruStore = MemoryLruStoreWrapper()
        val database = FakeComplexDatabase()
        val api = FakeComplexApi()

        val databaseStore = Store.by<NoteMarketKey, NoteMarketInput, NoteMarketOutput>(
            reader = { key -> database.read(key) },
            writer = { key, input -> database.write(key, input) },
            deleter = { key -> database.delete(key) },
            clearer = { database.clear() },
        )

        val bookkeeper = bookkeeper(database)
        val fetcher = fetcher(api)
        fun updater(
            onCompletion: OnNetworkCompletion<NoteMarketInput> = OnNetworkCompletion(
                onSuccess = {},
                onFailure = {}
            )
        ) = updater(api, onCompletion = onCompletion)
    }

    object Failure {
        val database = FakeComplexDatabase()
        val api = FakeComplexApi()

        val memoryLruCacheStore = Store.by<NoteMarketKey, NoteMarketInput, NoteMarketOutput>(
            reader = { throw Exception() },
            writer = { _, _ -> throw Exception() },
            deleter = { throw Exception() },
            clearer = { throw Exception() },
        )

        val databaseStore = Store.by<NoteMarketKey, NoteMarketInput, NoteMarketOutput>(
            reader = { throw Exception() },
            writer = { _, _ -> throw Exception() },
            deleter = { throw Exception() },
            clearer = { throw Exception() },
        )

        val bookkeeper = bookkeeper(database)
        val fetcher = fetcher(api, fail = true)

        fun updater(
            onCompletion: OnNetworkCompletion<NoteMarketInput> = OnNetworkCompletion(
                onSuccess = {},
                onFailure = {}
            )
        ) = NetworkUpdater.by<NoteMarketKey, NoteMarketInput>(
            post = { key, input -> api.post(key, input) },
            onCompletion = onCompletion
        )
    }

    private fun bookkeeper(database: FakeComplexDatabase) = Bookkeeper.by<NoteMarketKey>(
        read = { key -> database.getLastWriteTime(key.toString()) },
        write = { key, timestamp -> database.setLastWriteTime(key.toString(), timestamp) },
        delete = { key -> database.deleteWriteRequest(key.toString()) },
        deleteAll = { database.deleteAllWriteRequests() }
    )

    private fun fetcher(api: FakeComplexApi, fail: Boolean = false) =
        NetworkFetcher.by<NoteMarketKey, NoteMarketInput>(
            get = { key -> api.get(key, fail) }
        )

    private fun updater(
        api: FakeComplexApi,
        fail: Boolean = false,
        onCompletion: OnNetworkCompletion<NoteMarketInput> = OnNetworkCompletion(
            onSuccess = {},
            onFailure = {}
        )
    ) =
        NetworkUpdater.by<NoteMarketKey, NoteMarketInput>(
            post = { key, input -> api.post(key, input, fail) },
            onCompletion = onCompletion
        )
}
