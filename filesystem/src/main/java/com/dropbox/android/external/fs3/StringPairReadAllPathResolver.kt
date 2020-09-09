package com.dropbox.android.external.fs3

object StringPairReadAllPathResolver : PathResolver<Pair<String, String>> {
    override fun resolve(key: Pair<String, String>): String = key.first + "/" + key.second
}
