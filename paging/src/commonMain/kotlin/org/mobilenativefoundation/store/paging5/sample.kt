package org.mobilenativefoundation.store.paging5

import androidx.compose.runtime.Composable
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.Store


sealed class PostPagingData: Identifiable<String> {
    data class Post(val postId: String, val title: String) : Identifiable.Single<String>, PostPagingData() {
        override val id: String get() = postId
    }

    data class Feed(val posts: List<Post>) : Identifiable.Collection<String>, PostPagingData() {
        override val items: List<Identifiable.Single<String>> get() = posts
    }
}


sealed class PostPagingKey: StoreKey<String> {
    data class Key(
        override val cursor: String,
        override val size: Int,
        override val sort: StoreKey.Sort?,
        override val filters: List<StoreKey.Filter<*>>?
    ) : StoreKey.Collection.Cursor<String>, PostPagingKey()

}



class PostPagingStoreFactory {

    private fun createFetcher(): Fetcher<PostPagingKey, PostPagingData> = TODO()

    fun create(): Store<PostPagingKey, PostPagingData> = TODO()
}


@Composable
fun FeedView(store: Store<StoreKey<String>, Identifiable<String>>) {
    store.cursor<String, PostPagingData.Post>(
        key = PostPagingKey.Key("", 1, null, null),
        initialContent = {},
        loadingContent = {},
        errorContent = {},
        onPrefetch = {_ -> PostPagingKey.Key("", 1, null, null)}
    ) {

    }
}