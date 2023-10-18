package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.core5.InsertionStrategy
import org.mobilenativefoundation.store.core5.StoreData

sealed class PostData : StoreData<String> {
    data class Post(val postId: String, val title: String) : StoreData.Single<String>, PostData() {
        override val id: String get() = postId
    }

    data class Feed(val posts: List<Post>) : StoreData.Collection<String, Post>, PostData() {
        override val items: List<Post> get() = posts
        override fun copyWith(items: List<Post>): StoreData.Collection<String, Post> = copy(posts = items)
        override fun insertItems(strategy: InsertionStrategy, items: List<Post>): StoreData.Collection<String, Post> {

            return when (strategy) {
                InsertionStrategy.APPEND -> {
                    val updatedItems = items.toMutableList()
                    updatedItems.addAll(posts)
                    copyWith(items = updatedItems)
                }

                InsertionStrategy.PREPEND -> {
                    val updatedItems = posts.toMutableList()
                    updatedItems.addAll(items)
                    copyWith(items = updatedItems)
                }
            }
        }
    }
}



