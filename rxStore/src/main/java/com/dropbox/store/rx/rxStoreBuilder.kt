package com.dropbox.store.rx

import com.dropbox.android.external.store4.BuilderImpl
import com.dropbox.android.external.store4.BuilderWithSourceOfTruth
import com.dropbox.android.external.store4.ExperimentalStoreApi

import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse

import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.rxSingle

interface RxStore<Key : Any, Output : Any> {

    /**
     * Return a flow for the given key
     * @param request - see [StoreRequest] for configurations
     */
    fun stream(request: StoreRequest<Key>): Flowable<StoreResponse<Output>>

    /**
     * Purge a particular entry from memory and disk cache.
     * Persistent storage will only be cleared if a delete function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    fun clear(key: Key): Single<Unit>

    /**
     * Purge all entries from memory and disk cache.
     * Persistent storage will only be cleared if a deleteAll function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    @ExperimentalStoreApi
    fun clearAll(): Single<Unit>
}

interface RxStoreBuilder<Key : Any, Output : Any> {

    companion object {
        /**
         * Creates a new [StoreBuilder] from a non-[Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an HTTP-like single response per
         * request protocol.
         *
         * @param fetcher a function for fetching network records.
         */
        fun <Key : Any, Output : Any> fromSingle(
            fetcher: (key: Key) -> Single<Output>
        ): BuilderImpl<Key, Output> = BuilderImpl { key: Key ->
            fetcher.invoke(key).toFlowable().cache().asFlow()
        }

        /**
         * Creates a new [StoreBuilder] from a [Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
         * per request protocol.
         *
         * @param fetcher a function for fetching a flow of network records.
         */
        fun <Key : Any, Output : Any> fromFlowable(
            fetcher: (key: Key) -> Flowable<Output>
        ): StoreBuilder<Key, Output> = BuilderImpl { key: Key ->
            fetcher.invoke(key).asFlow()
        }
    }
}

val fetcher: (Int) -> Single<String> = { Single.just("My Key is $it") }
val reader: (Int) -> Maybe<String> = { Maybe.just("My Key from Persister is $it") }
val writer: (Int, String) -> Single<Unit> = { key, value -> Single.just(Unit) }
val rxStore: RxStore<Int, String> = RxStoreBuilder.fromSingle(fetcher)
    // .withFlowablePersister(
    //     reader = {
    //         Flowable.just(
    //             "My Key from Persister is $it",
    //             "My 2nd Key from Persister is $it"
    //         )
    //     },
    //     writer = { key, value -> Single.just(Unit) }
    // )
    .withSinglePersister(
        reader = reader,
        writer = writer
    )
    .build()
    .asRxStore()

fun <Key : Any, Output : Any> Store<Key, Output>.asRxStore(): RxStore<Key, Output> =
    object : RxStore<Key, Output> {
        override fun stream(request: StoreRequest<Key>): Flowable<StoreResponse<Output>> =
            this@asRxStore.stream(request).asFlowable()

        override fun clear(key: Key): Single<Unit> =
            rxSingle { this@asRxStore.clear(key) }

        @ExperimentalStoreApi
        override fun clearAll(): Single<Unit> = rxSingle { this@asRxStore.clearAll() }
    }

fun <Key : Any, Output : Any, NewOutput : Any> BuilderImpl<Key, Output>.withSinglePersister(
    reader: (Key) -> Maybe<NewOutput>,
    writer: (Key, Output) -> Single<Unit>,
    delete: ((Key) -> Unit)? = null,
    deleteAll: (() -> Unit)? = null
): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
    return nonFlowingPersister(
        reader = { key -> reader.invoke(key).await() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = { key -> delete?.invoke(key) },
        deleteAll = { deleteAll?.invoke() }
    )
}

fun <Key : Any, Output : Any, NewOutput : Any> BuilderImpl<Key, Output>.withFlowablePersister(
    reader: (Key) -> Flowable<NewOutput>,
    writer: (Key, Output) -> Single<Unit>,
    delete: ((Key) -> Unit)? = null,
    deleteAll: (() -> Unit)? = null
) {
    persister(
        reader = { key -> reader.invoke(key).asFlow() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = { key -> delete?.invoke(key) },
        deleteAll = { deleteAll?.invoke() }
    )
}


