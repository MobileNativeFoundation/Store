@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.Fetcher
import com.dropbox.external.store5.OnMarketCompletion
import com.dropbox.external.store5.OnRemoteCompletion
import com.dropbox.external.store5.Reader
import com.dropbox.external.store5.Updater
import com.dropbox.external.store5.Writer
import com.dropbox.external.store5.fake.api.Api
import kotlinx.datetime.Clock
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

internal class FakeFactory<Key : Any, Input : Any, Output : Any>(private val api: Api<Key, Output>) {

    fun buildFetcher(fail: Boolean = false): Fetcher<Key, Input, Output> = Fetcher(
        get = { key -> api.get(key, fail) },
        post = { key, input -> api.post(key, input as Output, fail) },
        converter = { it as Input }
    )

    @OptIn(InternalSerializationApi::class)
    inline fun <reified T : Output> buildReader(
        key: Key,
        refresh: Boolean = false,
        fail: Boolean = false,
        onCompletionsProducer: () -> List<OnMarketCompletion<Output>> = { listOf() }
    ) =
        Reader(
            key = key,
            fetcher = buildFetcher(fail),
            refresh = refresh,
            serializer = T::class.serializer() as KSerializer<Output>,
            onCompletions = onCompletionsProducer()
        )

    fun buildUpdater(fail: Boolean = false, onCompletion: OnRemoteCompletion<Output>? = null) =
        Updater<Key, Output, Output>(
            post = { key, input -> api.post(key, input, fail) },
            onCompletion = onCompletion ?: doNothingOnCompletion(),
            converter = { it },
            created = Clock.System.now().epochSeconds
        )

    @OptIn(InternalSerializationApi::class)
    inline fun <reified T : Output> buildWriter(
        key: Key,
        input: Output,
        fail: Boolean = false,
        onCompletionsProducer: () -> List<OnMarketCompletion<Output>> = { listOf() },
        postOnCompletion: OnRemoteCompletion<Output>? = null
    ) =
        Writer(
            key = key,
            input = input,
            updater = buildUpdater(fail, postOnCompletion),
            serializer = T::class.serializer() as KSerializer<Output>,
            onCompletions = onCompletionsProducer()
        )

    private fun <T : Any> doNothingOnCompletion() = OnRemoteCompletion<T>(
        onSuccess = {},
        onFailure = {}
    )
}