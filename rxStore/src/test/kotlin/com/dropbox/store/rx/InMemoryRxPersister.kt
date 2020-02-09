package com.dropbox.store.rx

import io.reactivex.Maybe
import io.reactivex.Single

/**
 * An in-memory non-flowing persister for testing.
 */
class InMemoryRxPersister<Key, Output> {
    private val data = mutableMapOf<Key, Output>()

    @Suppress("RedundantSuspendModifier") // for function reference
     fun read(key: Key): Maybe<Output> {
        return Maybe.fromCallable {data[key]}
     }

    @Suppress("RedundantSuspendModifier") // for function reference
     fun write(key: Key, output: Output): Single<Unit> {
        return Single.fromCallable {
            data[key] = output
        }
     }
}
