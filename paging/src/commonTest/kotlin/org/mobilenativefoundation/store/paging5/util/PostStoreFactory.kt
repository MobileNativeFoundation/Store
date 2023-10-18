@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store.paging5.util

import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.cache5.Cache
import org.mobilenativefoundation.store.paging5.KeyProvider
import org.mobilenativefoundation.store.paging5.PagingCache
import org.mobilenativefoundation.store.paging5.StoreKey
import org.mobilenativefoundation.store.store5.*
import kotlin.math.floor

typealias PostStore = MutableStore<PostKey, PostData>

class PostStoreFactory(private val api: PostApi, private val db: PostDatabase) {

    private fun createFetcher(): Fetcher<PostKey, PostData> = Fetcher.of { key ->
        println("HITTING IN FETCHER")
        when (key) {
            is PostKey.Single -> {
                when (val result = api.get(key.id)) {
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
                when (val result = api.get(key.cursor, key.size)) {
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
            println("HITTING IN SOT")
            flow {
                when (key) {
                    is PostKey.Single -> {
                        val post = db.findPostByPostId(key.id)
                        emit(post)
                    }

                    is PostKey.Cursor -> {
                        val feed = db.findFeedByUserId(key.cursor, key.size)
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
                    db.add(data)
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

    private fun createPagingCacheKeyProvider(): KeyProvider<String, PostData.Post> =
        object : KeyProvider<String, PostData.Post> {
            override fun from(key: StoreKey.Collection<String>, value: PostData.Post): StoreKey.Single<String> {
                return PostKey.Single(value.postId)
            }

            override fun from(key: StoreKey.Single<String>, value: PostData.Post): StoreKey.Collection<String> {
                val id = value.postId.toInt()
                val cursor = (floor(id.toDouble() / 10) * 10) + 1
                return PostKey.Cursor(cursor.toInt().toString(), 10)
            }

        }

    private fun createMemoryCache(): Cache<PostKey, PostData> =
        PagingCache(createPagingCacheKeyProvider())

    fun create(): MutableStore<PostKey, PostData> = StoreBuilder.from(
        fetcher = createFetcher(),
        sourceOfTruth = createSourceOfTruth(),
        memoryCache = createMemoryCache()
    ).toMutableStoreBuilder(
        converter = createConverter()
    ).build(
        updater = createUpdater(),
        bookkeeper = null
    )
}