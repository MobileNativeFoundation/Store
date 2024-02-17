package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.DataJoiner
import org.mobilenativefoundation.store.store5.StoreReadResponse

@OptIn(ExperimentalStoreApi::class)
class PostDataJoiner : DataJoiner<String, PostKey, PostData.Feed, PostData.Post> {
    override suspend fun invoke(
        key: PostKey,
        data: Map<PostKey, StoreReadResponse.Data<PostData.Feed>?>
    ): StoreReadResponse.Data<PostData.Feed> {
        var combinedItems = mutableListOf<PostData.Post>()

        data.values.forEach { responseData ->
            println("RESPONSE DATA = $responseData")
            responseData?.value?.items?.let { items ->
                combinedItems = (combinedItems + items).distinctBy { it.postId }.toMutableList()
            }
        }

        return (key as? PostKey.Cursor)?.let {
            val feed = PostData.Feed(combinedItems)
            data.values.last { it != null }?.let {
                StoreReadResponse.Data(feed, it.origin)
            }
        } ?: throw IllegalArgumentException("Key must be a Collection type")
    }

}