package com.dropbox.android.sample.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RedditData(
    val data: Data,
    val kind: String
)

@Serializable
data class Children(
    val data: Post
)

@Serializable
data class Data(
    val children: List<Children>
)

@Serializable
data class Post(
    val id: String,
    val preview: Preview? = null,
    val title: String,
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
) {
    fun nestedThumbnail(): Image? {
        return preview?.images?.getOrNull(0)?.source
    }
}

@Serializable
data class Preview(
    val images: List<Images>
)

@Serializable
data class Images(
    val source: Image
)

@Serializable
data class Image(
    val url: String,
    val height: Int,
    val width: Int
)
