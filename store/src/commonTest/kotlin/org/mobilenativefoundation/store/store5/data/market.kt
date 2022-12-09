package org.mobilenativefoundation.store.store5.data

import org.mobilenativefoundation.store.store5.Market
import org.mobilenativefoundation.store.store5.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.data.fake.FakeComplexMarket
import org.mobilenativefoundation.store.store5.data.fake.FakeMarket
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput

internal fun market(
    failRead: Boolean = false,
    failWrite: Boolean = false,
    onNetworkCompletion: OnNetworkCompletion<Note> = OnNetworkCompletion(
        onSuccess = {},
        onFailure = {}
    )
): Market<String, Note, Note> = when {
    failWrite && failRead -> Market.of(
        stores = listOf(FakeMarket.Failure.memoryLruCacheStore, FakeMarket.Failure.databaseStore),
        bookkeeper = FakeMarket.Failure.bookkeeper,
        updater = FakeMarket.Failure.updater,
        fetcher = FakeMarket.Failure.fetcher
    )

    failWrite -> Market.of(
        stores = listOf(FakeMarket.Success.memoryLruCacheStore, FakeMarket.Success.databaseStore),
        bookkeeper = FakeMarket.Failure.bookkeeper,
        updater = FakeMarket.Failure.updater,
        fetcher = FakeMarket.Success.fetcher
    )

    failRead -> Market.of(
        stores = listOf(FakeMarket.Success.memoryLruCacheStore, FakeMarket.Success.databaseStore),
        bookkeeper = FakeMarket.Failure.bookkeeper,
        updater = FakeMarket.Success.updater(),
        fetcher = FakeMarket.Failure.fetcher
    )

    else -> Market.of(
        stores = listOf(FakeMarket.Success.memoryLruCacheStore, FakeMarket.Success.databaseStore),
        bookkeeper = FakeMarket.Success.bookkeeper,
        updater = FakeMarket.Success.updater(onNetworkCompletion),
        fetcher = FakeMarket.Success.fetcher
    )
}

internal fun complexMarket(
    failRead: Boolean = false,
    failWrite: Boolean = false,
    onNetworkCompletion: OnNetworkCompletion<NoteMarketOutput> = OnNetworkCompletion(
        onSuccess = {},
        onFailure = {}
    )
): Market<NoteMarketKey, NoteMarketInput, NoteMarketOutput> = when {
    failWrite && failRead -> Market.of(
        stores = listOf(FakeComplexMarket.Failure.memoryLruCacheStore, FakeComplexMarket.Failure.databaseStore),
        bookkeeper = FakeComplexMarket.Failure.bookkeeper,
        updater = FakeComplexMarket.Failure.updater(),
        fetcher = FakeComplexMarket.Failure.fetcher
    )

    failWrite -> Market.of(
        stores = listOf(FakeComplexMarket.Success.memoryLruStore, FakeComplexMarket.Success.databaseStore),
        bookkeeper = FakeComplexMarket.Failure.bookkeeper,
        updater = FakeComplexMarket.Failure.updater(),
        fetcher = FakeComplexMarket.Success.fetcher
    )

    failRead -> Market.of(
        stores = listOf(FakeComplexMarket.Success.memoryLruStore, FakeComplexMarket.Success.databaseStore),
        bookkeeper = FakeComplexMarket.Failure.bookkeeper,
        updater = FakeComplexMarket.Success.updater(),
        fetcher = FakeComplexMarket.Failure.fetcher
    )

    else -> Market.of(
        stores = listOf(FakeComplexMarket.Success.memoryLruStore, FakeComplexMarket.Success.databaseStore),
        bookkeeper = FakeComplexMarket.Success.bookkeeper,
        updater = FakeComplexMarket.Success.updater(onNetworkCompletion),
        fetcher = FakeComplexMarket.Success.fetcher
    )
}