package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.InsertionStrategy
import org.mobilenativefoundation.store.core5.StoreData

@OptIn(ExperimentalStoreApi::class)
sealed class PostData : StoreData<String> {
    data class Post(val postId: String, val title: String) : StoreData.Single<String>, PostData() {
        override val id: String get() = postId
    }

    data class Feed(
        val posts: List<Post>,
        override val prevKey: PostKey.Cursor,
        override val nextKey: PostKey.Cursor?,
    ) : StoreData.Collection<String, PostKey.Cursor, Post>, PostData() {
        override val items: List<Post> get() = posts
        override val itemsAfter: Int? = null
        override val itemsBefore: Int? = null

        override fun copyWith(items: List<Post>): StoreData.Collection<String, PostKey.Cursor, Post> = copy(posts = items)

        override fun insertItems(
            strategy: InsertionStrategy,
            items: List<Post>,
        ): StoreData.Collection<String, PostKey.Cursor, Post> {
            return when (strategy) {
                InsertionStrategy.APPEND -> {
                    val updatedItems = posts.toMutableList()
                    updatedItems.addAll(items)
                    copyWith(items = updatedItems)
                }

                InsertionStrategy.PREPEND -> {
                    val updatedItems = items.toMutableList()
                    updatedItems.addAll(posts)
                    copyWith(items = updatedItems)
                }

                InsertionStrategy.REPLACE -> {
                    copyWith(items = posts)
                }
            }
        }
    }
}
