package com.dropbox.market.notes.android.data.repository

import com.dropbox.market.notes.android.Key
import com.dropbox.market.notes.android.data.Notebook
import com.dropbox.market.notes.android.data.api.Api
import com.dropbox.store.Fetch
import com.dropbox.store.Market
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.lastOrNull
import javax.inject.Inject

class NotesRepository @Inject constructor(
    private val coroutineDispatcher: CoroutineDispatcher,
    private val api: Api<Key, Notebook>,
    private val market: Market<Key>
) {

    private val coroutineScope = CoroutineScope(coroutineDispatcher)

    suspend fun get(key: Key): Market.Response<Notebook>? = market.read(buildReader(key)).lastOrNull()

    suspend fun read(key: Key): MutableSharedFlow<Market.Response<Notebook>> = market.read(buildReader(key))

    suspend fun getStatus(): Int = api.getStatus()

    suspend fun post(key: Key, notebook: Notebook): Boolean {
        return try {
            market.write(buildWriter(key, notebook))
            true
        } catch (throwable: Throwable) {
            false
        }
    }

    private fun buildReader(key: Key) = Market.Request.Read.Builder<Key, Notebook, Notebook>(key = key, refresh = true)
        .request(buildGetRequest())
        .build<Notebook>()

    private fun buildGetRequest() = Fetch.Request.Get.Builder<Key, Notebook, Notebook>()
        .get { key -> api.get(key) }
        .post { _, input ->
            when (input) {
                is Notebook.Note -> if (input.value != null) {
                    api.post(input.value)
                }

                is Notebook.Notes -> input.values.forEach { marketNote -> api.post(marketNote) }
            }
            input
        }
        .converter { it }
        .build()

    private fun buildWriter(key: Key, notebook: Notebook) =
        Market.Request.Write.Builder<Key, Notebook, Notebook>(key, notebook)
            .request(buildPostRequest())
            .onCompletion(Market.Request.Write.OnCompletion.Builder<Notebook>().onSuccess { }.onFailure {}.build())
            .build<Notebook>()

    private fun buildPostRequest() = Fetch.Request.Post.Builder<Key, Notebook, Notebook>()
        .post { _, input ->
            when (input) {
                is Notebook.Note -> if (input.value != null) {
                    api.post(input.value)
                }

                is Notebook.Notes -> input.values.forEach { marketNote -> api.post(marketNote) }
            }

            input
        }
        .onCompletion(Fetch.OnCompletion.Builder<Notebook>().onSuccess { }.onFailure { }.build())
        .converter { it }
        .build()
}