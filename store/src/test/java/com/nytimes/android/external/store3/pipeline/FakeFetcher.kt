package com.nytimes.android.external.store3.pipeline

import org.assertj.core.api.Assertions

class FakeFetcher<Key, Output>(
        vararg val responses: Pair<Key, Output>
) {
    private var index = 0
    @Suppress("RedundantSuspendModifier") // needed for function reference
    suspend fun fetch(key: Key): Output {
        if (index >= responses.size) {
            throw AssertionError("unexpected fetch request")
        }
        val pair = responses[index++]
        Assertions.assertThat(pair.first).isEqualTo(key)
        return pair.second
    }
}