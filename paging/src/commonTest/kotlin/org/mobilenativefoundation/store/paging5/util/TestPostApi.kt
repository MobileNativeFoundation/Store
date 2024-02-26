package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

@OptIn(ExperimentalStoreApi::class)
class TestPostApi : PostApi {

    private val posts = mutableMapOf<String, PostData.Post>()
    private val postsList = mutableListOf<PostData.Post>()

    init {
        (1..50).forEach {
            val id = it.toString()
            posts[id] = PostData.Post(id, id)
            postsList.add(PostData.Post(id, id))
        }
    }

    override suspend fun get(key: PostKey.Single): PostGetRequestResult {
        val post = posts[key.id]
        return if (post != null) {
            PostGetRequestResult.Data(post)
        } else {
            PostGetRequestResult.Error.Message("Post ${key.id} was not found")
        }
    }

    override suspend fun get(key: PostKey.Cursor): FeedGetRequestResult {

        val firstIndexInclusive = postsList.indexOfFirst { it.postId == key.cursor }
        val lastIndexExclusive = firstIndexInclusive + key.size

        val (posts, nextCursor) = if (lastIndexExclusive > postsList.lastIndex) {
            val posts = postsList.subList(firstIndexInclusive, postsList.size)
            val nextCursor = null
            posts to nextCursor
        } else {
            val posts = postsList.subList(firstIndexInclusive, lastIndexExclusive)
            val nextCursor = postsList[lastIndexExclusive].id

            posts to nextCursor
        }

        return FeedGetRequestResult.Data(
            PostData.Feed(
                posts = posts,
                prevKey = key,
                nextKey = key.copy(cursor = nextCursor)
            )
        )

    }

    override suspend fun put(post: PostData.Post): PostPutRequestResult {
        posts[post.id] = post
        return PostPutRequestResult.Data(post)
    }
}
