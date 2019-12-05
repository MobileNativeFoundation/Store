package com.dropbox.android.sample.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RedditData(
    val data: Data,
    val kind: String
)

@JsonClass(generateAdapter = true)
data class Children(
    val data: Post
)

@JsonClass(generateAdapter = true)
data class Data(
    val children: List<Children>
)

@JsonClass(generateAdapter = true)
data class Post(
    val id: String,
    val preview: Preview?,
    val title: String,
    val url: String,
    val height: Int?,
    val width: Int?
) {
    fun nestedThumbnail(): Image? {
        return preview?.images?.getOrNull(0)?.source
    }
}

@JsonClass(generateAdapter = true)
data class Preview(
    val images: List<Images>
)

@JsonClass(generateAdapter = true)
data class Images(
    val source: Image
)

@JsonClass(generateAdapter = true)
data class Image(
    val url: String,
    val height: Int,
    val width: Int
)
