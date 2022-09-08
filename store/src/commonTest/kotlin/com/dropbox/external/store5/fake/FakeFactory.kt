@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.Fetch
import com.dropbox.external.store5.Market
import com.dropbox.external.store5.definition.Fetcher
import com.dropbox.external.store5.fake.api.Api
import kotlinx.datetime.Clock
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

internal class FakeFactory<Key : Any, Input : Any, Output : Any>(private val api: Api<Key, Output>) {

    fun buildFetcher(fail: Boolean = false): Fetcher<Key, Input, Output> = Fetch.Request.Get(
        get = { key -> api.get(key, fail) },
        post = { key, input -> api.post(key, input as Output, fail) },
        converter = { it as Input }
    )

    @OptIn(InternalSerializationApi::class)
    inline fun <reified T : Output> buildReader(
        key: Key,
        refresh: Boolean = false,
        fail: Boolean = false,
        onCompletionsProducer: () -> List<Market.Request.Reader.OnCompletion<Output>> = { listOf() }
    ) =
        Market.Request.Reader(
            key = key,
            request = buildFetcher(fail),
            refresh = refresh,
            serializer = T::class.serializer() as KSerializer<Output>,
            onCompletions = onCompletionsProducer()
        )

    fun buildPostRequest(fail: Boolean = false, onCompletion: Fetch.OnCompletion<Output>? = null) =
        Fetch.Request.Post<Key, Output, Output>(
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
        onCompletionsProducer: () -> List<Market.Request.Writer.OnCompletion<Output>> = { listOf() },
        postOnCompletion: Fetch.OnCompletion<Output>? = null
    ) =
        Market.Request.Writer<Key, Output, Output>(
            key = key,
            input = input,
            request = buildPostRequest(fail, postOnCompletion),
            serializer = T::class.serializer() as KSerializer<Output>,
            onCompletions = onCompletionsProducer()
        )

    private fun <T : Any> doNothingOnCompletion() = Fetch.OnCompletion<T>(
        onSuccess = {},
        onFailure = {}
    )
}