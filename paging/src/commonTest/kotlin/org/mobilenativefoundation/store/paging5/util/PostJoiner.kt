package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.Joiner
import org.mobilenativefoundation.store.paging5.PagingData

@OptIn(ExperimentalStoreApi::class)
class PostJoiner : Joiner<String, PostKey, PostData.Post> {
    override suspend fun invoke(data: Map<PostKey, PagingData<String, PostData.Post>>): PagingData<String, PostData.Post> {
        var combinedItems = mutableListOf<PostData.Post>()

        data.values.forEach { responseData ->
            responseData.items.let { items ->
                combinedItems = (combinedItems + items).distinctBy { it.postId }.toMutableList()
            }
        }

        return PagingData(combinedItems)
    }
}
