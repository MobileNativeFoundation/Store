package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.paging5.Identifiable
import org.mobilenativefoundation.store.paging5.StoreKey

sealed class PostData : Identifiable<String> {
    data class Post(val postId: String, val title: String) : Identifiable.Single<String>, PostData() {
        override val id: String get() = postId
    }

    data class Feed(val posts: List<Post>) : Identifiable.Collection<String, Post>, PostData() {
        override val items: List<Post> get() = posts
        override fun copyWith(items: List<Post>): Identifiable.Collection<String, Post> = copy(posts = items)
        override fun insertItems(type: StoreKey.LoadType, items: List<Post>): Identifiable.Collection<String, Post> {

            return when (type) {
                StoreKey.LoadType.APPEND -> {
                    val updatedItems = items.toMutableList()
                    updatedItems.addAll(posts)
                    copyWith(items = updatedItems)
                }

                StoreKey.LoadType.PREPEND -> {
                    val updatedItems = posts.toMutableList()
                    updatedItems.addAll(items)
                    copyWith(items = updatedItems)
                }
            }
        }
    }
}



