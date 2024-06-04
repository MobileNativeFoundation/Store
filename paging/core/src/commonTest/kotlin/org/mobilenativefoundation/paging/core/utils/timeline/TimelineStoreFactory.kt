package org.mobilenativefoundation.paging.core.utils.timeline

import org.mobilenativefoundation.paging.core.PagingData
import org.mobilenativefoundation.paging.core.PagingKey
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult


@OptIn(ExperimentalStoreApi::class)
class TimelineStoreFactory(
    private val feedService: FeedService,
    private val postService: PostService,
) {

    private fun createFetcher(): Fetcher<PK, PD> = Fetcher.of { key ->


        when (val params = key.params) {
            is TimelineKeyParams.Collection -> {
                val ck = PagingKey(key.key, params)
                val feed = feedService.get(ck)
                PagingData.Collection(
                    items = feed.posts.map { post -> PagingData.Single(post.id, post) },
                    itemsBefore = feed.itemsBefore,
                    itemsAfter = feed.itemsAfter,
                    prevKey = key,
                    nextKey = feed.nextKey
                )
            }

            is TimelineKeyParams.Single -> {
                val sk = PagingKey(key.key, params)
                val post = postService.get(sk)
                if (post == null) {
                    throw Throwable("Post is null")
                } else {
                    PagingData.Single(post.id, post)
                }
            }
        }
    }

    private fun createConverter(): Converter<PD, PD, PD> = Converter.Builder<PD, PD, PD>()
        .fromOutputToLocal { it }
        .fromNetworkToLocal { it }
        .build()

    private fun createUpdater(): Updater<PK, PD, Any> = Updater.by(
        post = { key, value ->
            when (val params = key.params) {
                is TimelineKeyParams.Single -> {
                    if (value is PagingData.Single) {
                        val updatedValue = value.data
                        if (updatedValue is TimelineData.Post) {
                            val sk = PagingKey(key.key, params)
                            val response = postService.update(sk, updatedValue)
                            UpdaterResult.Success.Typed(response)
                        } else {
                            UpdaterResult.Error.Message("Updated value is the wrong type. Expected ${TimelineData.Post::class}, received ${updatedValue::class}")
                        }
                    } else {
                        UpdaterResult.Error.Message("Updated value is the wrong type. Expected ${PagingData.Single::class}, received ${value::class}")
                    }
                }

                is TimelineKeyParams.Collection -> throw UnsupportedOperationException("Updating collections is not supported")
            }
        },
    )

    fun create(): MutableStore<PK, PD> =
        StoreBuilder.from(
            fetcher = createFetcher()
        ).toMutableStoreBuilder(
            converter = createConverter()
        ).build(
            updater = createUpdater(),
            bookkeeper = null
        )
}