@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.MarketReader
import com.dropbox.external.store5.MarketWriter
import com.dropbox.external.store5.NetworkFetcher
import com.dropbox.external.store5.NetworkUpdater
import com.dropbox.external.store5.OnMarketCompletion
import com.dropbox.external.store5.OnNetworkCompletion
import com.dropbox.external.store5.fake.api.Api
import kotlinx.datetime.Clock

internal class FakeFactory<Key : Any, Input : Any, Output : Any>(private val api: Api<Key, Output>) {

    fun buildFetcher(fail: Boolean = false): NetworkFetcher<Key, Input, Output> = NetworkFetcher.by(
        get = { key -> api.get(key, fail) },
        post = { key, input -> api.post(key, input as Output, fail) },
        converter = { it as Input }
    )

    inline fun <reified T : Output> buildReader(
        key: Key,
        refresh: Boolean = false,
        fail: Boolean = false,
        onCompletionsProducer: () -> List<OnMarketCompletion<Output>> = { listOf() }
    ) = MarketReader.by(
        key = key,
        fetcher = buildFetcher(fail),
        refresh = refresh,
        onCompletions = onCompletionsProducer()
    )

    fun buildUpdater(fail: Boolean = false, onCompletion: OnNetworkCompletion<Output>? = null) =
        NetworkUpdater.by<Key, Output, Output>(
            post = { key, input -> api.post(key, input, fail) },
            onCompletion = onCompletion ?: doNothingOnCompletion(),
            converter = { it },
            created = Clock.System.now().epochSeconds
        )

    private fun <T : Any> doNothingOnCompletion() = OnNetworkCompletion<T>(
        onSuccess = {},
        onFailure = {}
    )

    inline fun <reified T : Output> buildWriter(
        key: Key,
        input: Output,
        fail: Boolean = false,
        onCompletionsProducer: () -> List<OnMarketCompletion<Output>> = { listOf() },
        postOnCompletion: OnNetworkCompletion<Output>? = null
    ) = MarketWriter.by(
        key = key,
        input = input,
        updater = buildUpdater(fail, postOnCompletion),
        onCompletions = onCompletionsProducer()
    )
}