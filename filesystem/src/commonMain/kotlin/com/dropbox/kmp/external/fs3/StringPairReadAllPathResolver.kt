package com.dropbox.kmp.external.fs3

val StringPairReadAllPathResolver: (Pair<String, String>) -> String = { it.first + "/" + it.second }
