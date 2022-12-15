package org.mobilenativefoundation.store.store5.data.fake

import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.NetworkFetcher
import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.impl.MemoryLruStore

internal object FakeMarket {
    object Failure {
        val database = FakeDatabase<Note>()
        val api = FakeApi()

        val memoryLruCacheStore = Store.by<String, Note, Note>(
            reader = { throw Exception() },
            writer = { _, _ -> throw Exception() },
            deleter = { throw Exception() },
            clearer = { throw Exception() }
        )

        val databaseStore = Store.by<String, Note, Note>(
            reader = { throw Exception() },
            writer = { _, _ -> throw Exception() },
            deleter = { throw Exception() },
            clearer = { throw Exception() },
        )

        val bookkeeper = bookkeeper(database)
        val fetcher = fetcher(api, fail = true)
        val updater = updater(api, fail = true)
    }

    object Success {
        val memoryLruStore = MemoryLruStore<String, Note>(10)
        val database = FakeDatabase<Note>()
        val api = FakeApi()

        val memoryLruCacheStore = Store.by<String, Note, Note>(
            reader = { key -> memoryLruStore.read(key) },
            writer = { key, input -> memoryLruStore.write(key, input) },
            deleter = { key -> memoryLruStore.delete(key) },
            clearer = { memoryLruStore.clear() },
        )

        val databaseStore = Store.by<String, Note, Note>(
            reader = { key -> database.read(key) },
            writer = { key, input -> database.write(key, input) },
            deleter = { key -> database.delete(key) },
            clearer = { database.clear() },
        )

        val bookkeeper = bookkeeper(database)
        val fetcher = fetcher(api)
        fun updater(
            onCompletion: OnNetworkCompletion<Note> = OnNetworkCompletion(
                onSuccess = {},
                onFailure = {}
            )
        ) = updater(api, onCompletion = onCompletion)
    }

    private fun bookkeeper(database: FakeDatabase<Note>) = Bookkeeper.by<String>(
        read = { key -> database.getLastWriteTime(key) },
        write = { key, timestamp -> database.setLastWriteTime(key, timestamp) },
        delete = { key -> database.deleteWriteRequest(key) },
        deleteAll = { database.deleteAllWriteRequests() }
    )

    private fun fetcher(api: FakeApi, fail: Boolean = false) =
        NetworkFetcher.by<String, Note, Note>(
            get = { key -> api.get(key, fail) },
            post = { key, input -> api.post(key, input, fail) },
            converter = { it }
        )

    private fun updater(
        api: FakeApi,
        fail: Boolean = false,
        onCompletion: OnNetworkCompletion<Note> = OnNetworkCompletion(
            onSuccess = {},
            onFailure = {}
        )
    ) =
        NetworkUpdater.by<String, Note, Note>(
            post = { key, input -> api.post(key, input, fail) },
            onCompletion = onCompletion,
            converter = { it }
        )
}
