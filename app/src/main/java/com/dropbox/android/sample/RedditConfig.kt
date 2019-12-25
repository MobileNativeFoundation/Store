package com.dropbox.android.sample

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RedditConfig(val limit: Int)
