package com.dropbox.store.rx3

import com.dropbox.android.external.store4.StoreBuilder
import io.reactivex.rxjava3.core.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.rx3.asCoroutineDispatcher

/**
 * Define what scheduler fetcher requests will be called on,
 * if a scheduler is not set Store will use [GlobalScope]
 */
fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.withScheduler(
    scheduler: Scheduler
): StoreBuilder<Key, Output> {
    return scope(CoroutineScope(scheduler.asCoroutineDispatcher()))
}
