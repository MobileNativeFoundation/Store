package com.dropbox.android.external.cache4

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Create and return a new coroutine dispatcher backed by a single thread.
 * This is used for testing concurrency behaviors.
 */
fun newSingleThreadDispatcher() =
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
