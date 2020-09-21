package com.dropbox.store.rx2

import com.dropbox.android.external.store4.StoreBuilder
import io.reactivex.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.rx2.asCoroutineDispatcher

/**
 * Define what scheduler fetcher requests will be called on,
 * if a scheduler is not set Store will use [GlobalScope]
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.withScheduler(
    scheduler: Scheduler
): StoreBuilder<Key, Output> {
    return scope(CoroutineScope(scheduler.asCoroutineDispatcher()))
}
