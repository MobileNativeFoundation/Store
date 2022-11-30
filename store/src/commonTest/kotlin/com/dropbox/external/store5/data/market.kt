package com.dropbox.external.store5.data

import com.dropbox.external.store5.Market
import com.dropbox.external.store5.OnNetworkCompletion
import com.dropbox.external.store5.data.fake.FakeMarket
import com.dropbox.external.store5.data.model.Note

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