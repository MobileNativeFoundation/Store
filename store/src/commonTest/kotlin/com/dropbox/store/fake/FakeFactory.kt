@file:Suppress("UNCHECKED_CAST")

package com.dropbox.store.fake

import com.dropbox.store.Fetch
import com.dropbox.store.Market
import com.dropbox.store.fake.api.Api

internal class FakeFactory<Key : Any, Input: Any, Output : Any>(private val api: Api<Key, Output>) {
    fun buildGetRequest(fail: Boolean = false) = Fetch.Request.Get.Builder<Key, Input, Output>()
        .get { key -> api.get(key, fail) }
        .post { key, input ->  api.post(key, input as Output, fail)}
        .converter { it as Input }
        .build()

    inline fun <reified T : Output> buildReader(key: Key, refresh: Boolean = false, fail: Boolean = false) =
        Market.Request.Read.Builder<Key, Input, Output>(key, refresh)
            .request(buildGetRequest(fail))
            .build<T>()

    inline fun <reified T : Output> buildReader(
        key: Key,
        refresh: Boolean = false,
        fail: Boolean = false,
        onCompletionsProducer: () -> List<Market.Request.Read.OnCompletion<Output>>
    ) =
        Market.Request.Read.Builder<Key, Input, Output>(key, refresh)
            .request(buildGetRequest(fail))
            .apply { onCompletionsProducer().forEach { onCompletion(it) } }
            .build<T>()

    private fun <T : Any> buildOnCompletion(
        onSuccess: (Fetch.Result.Success<T>) -> Unit,
        onFailure: (Fetch.Result.Failure) -> Unit
    ) = Fetch.OnCompletion.Builder<T>()
        .onSuccess(onSuccess)
        .onFailure(onFailure)
        .build()

    fun buildPostRequest(fail: Boolean = false, onCompletion: Fetch.OnCompletion<Output>? = null) = Fetch.Request.Post.Builder<Key, Output, Output>()
        .post { key, input -> api.post(key, input, fail) }
        .onCompletion(onCompletion ?: doNothingOnCompletion)
        .converter { it }
        .build()

    inline fun <reified T : Output> buildWriter(key: Key, input: Output, fail: Boolean = false) =
        Market.Request.Write.Builder<Key, Output, Output>(key, input)
            .request(buildPostRequest(fail))
            .build<T>()

    inline fun <reified T : Output> buildWriter(
        key: Key,
        input: Output,
        fail: Boolean = false,
        postOnCompletion: Fetch.OnCompletion<Output>? = null,
        marketOnCompletionsProducer: () -> List<Market.Request.Write.OnCompletion<Output>>
    ) = Market.Request.Write.Builder<Key, Output, Output>(key, input)
        .request(buildPostRequest(fail, postOnCompletion))
        .apply { marketOnCompletionsProducer().forEach { onCompletion(it) } }
        .build<T>()

    private val doNothingOnCompletion = buildOnCompletion<Output>(
        onSuccess = {},
        onFailure = {}
    )
}