package com.nytimes.android.external.store4.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.assertj.core.api.Assertions


/**
 * Asserts only the [expected] items by just taking that many from the stream
 *
 * Use this when Pipeline has an infinite part (e.g. Persister or a never ending fetcher)
 */
suspend fun <T> Flow<T>.assertItems(vararg expected: T) {
    Assertions.assertThat(this.take(expected.size).toList())
            .isEqualTo(expected.toList())
}