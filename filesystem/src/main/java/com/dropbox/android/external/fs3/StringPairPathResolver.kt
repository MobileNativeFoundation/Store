package com.dropbox.android.external.fs3

object StringPairPathResolver : PathResolver<Pair<String, String>> {
    override fun resolve(key: Pair<String, String>): String = key.toString()
}
