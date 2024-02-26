@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store.paging5.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult

class PostStoreFactory(private val scope: CoroutineScope, private val api: PostApi, private val db: PostDatabase) {

    private fun createFetcher(): Fetcher<PostKey, PostData> = Fetcher.of { key ->
        when (key) {
            is PostKey.Single -> {
                when (val result = api.get(key)) {
                    is PostGetRequestResult.Data -> {
                        result.data
                    }

                    is PostGetRequestResult.Error.Exception -> {
                        throw Throwable(result.error)
                    }

                    is PostGetRequestResult.Error.Message -> {
                        throw Throwable(result.error)
                    }
                }
            }

            is PostKey.Cursor -> {
                when (val result = api.get(key)) {
                    is FeedGetRequestResult.Data -> {
                        result.data
                    }

                    is FeedGetRequestResult.Error.Exception -> {
                        throw Throwable(result.error)
                    }

                    is FeedGetRequestResult.Error.Message -> {
                        throw Throwable(result.error)
                    }
                }
            }
        }
    }

    private fun createSourceOfTruth(): SourceOfTruth<PostKey, PostData, PostData> = SourceOfTruth.of(
        reader = { key ->
            flow {
                when (key) {
                    is PostKey.Single -> {
                        val post = db.findPostByPostId(key.id)
                        emit(post)
                    }

                    is PostKey.Cursor -> {
                        val feed = db.findFeedByKey(key, key.size)
                        emit(feed)
                    }
                }
            }
        },
        writer = { key, data ->
            when {
                key is PostKey.Single && data is PostData.Post -> {
                    db.add(data)
                }

                key is PostKey.Cursor && data is PostData.Feed -> {
                    db.add(key, data)
                }
            }
        }
    )

    private fun createConverter(): Converter<PostData, PostData, PostData> =
        Converter.Builder<PostData, PostData, PostData>()
            .fromNetworkToLocal { it }
            .fromOutputToLocal { it }
            .build()

    private fun createUpdater(): Updater<PostKey, PostData, Boolean> = Updater.by(
        post = { key, data ->
            when {
                key is PostKey.Single && data is PostData.Post -> {
                    when (val result = api.put(data)) {
                        is PostPutRequestResult.Data -> UpdaterResult.Success.Typed(result)
                        is PostPutRequestResult.Error.Exception -> UpdaterResult.Error.Exception(result.error)
                        is PostPutRequestResult.Error.Message -> UpdaterResult.Error.Message(result.error)
                    }
                }

                else -> UpdaterResult.Error.Message("Unsupported: key: ${key::class}, data: ${data::class}")
            }
        }
    )

    fun create(): MutableStore<PostKey, PostData> = StoreBuilder.from(
        fetcher = createFetcher(),
        sourceOfTruth = createSourceOfTruth(),
    ).toMutableStoreBuilder(
        converter = createConverter()
    ).build(
        updater = createUpdater(),
        bookkeeper = null
    )
}
