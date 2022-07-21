package com.dropbox.kmp.external.fs3

import okio.BufferedSource

/**
 * Converts Java values to JSON.
 */
interface BufferedSourceAdapter<Parsed> {
    fun toJson(value: Parsed): BufferedSource
}
