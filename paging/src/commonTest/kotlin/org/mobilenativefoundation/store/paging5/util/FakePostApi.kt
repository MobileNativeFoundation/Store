package org.mobilenativefoundation.store.paging5.util

class FakePostApi : PostApi {
    private val posts = mutableMapOf<String, PostData.Post>()
    private val postsList = mutableListOf<PostData.Post>()

    init {
        (1..100).forEach {
            val id = it.toString()
            posts[id] = PostData.Post(id, id)
            postsList.add(PostData.Post(id, id))
        }
    }

    override suspend fun get(postId: String): PostGetRequestResult {
        val post = posts[postId]
        return if (post != null) {
            PostGetRequestResult.Data(post)
        } else {
            PostGetRequestResult.Error.Message("Post $postId was not found")
        }
    }

    override suspend fun get(
        cursor: String?,
        size: Int,
    ): FeedGetRequestResult {
        val firstIndexInclusive = postsList.indexOfFirst { it.postId == cursor }
        val lastIndexExclusive = firstIndexInclusive + size
        val posts = postsList.subList(firstIndexInclusive, lastIndexExclusive)
        return FeedGetRequestResult.Data(PostData.Feed(posts = posts))
    }

    override suspend fun put(post: PostData.Post): PostPutRequestResult {
        posts.put(post.id, post)
        return PostPutRequestResult.Data(post)
    }
}
