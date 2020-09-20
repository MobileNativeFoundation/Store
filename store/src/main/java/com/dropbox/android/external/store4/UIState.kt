package com.dropbox.android.external.store4

import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

data class UIState<T>(
        val loading: StoreResponse.Loading? = null,
        val data: StoreResponse.Data<T>? = null,
        val error: StoreResponse.Error? = null
) {
    fun isLoading() = loading != null
    internal fun combine(response: StoreResponse<T>) : UIState<T> {
        return when(response) {
            is StoreResponse.Loading -> {
                copy(
                        loading = response,
                        error = null
                )
            }
            is StoreResponse.Data -> {
                copy(
                        loading = null,
                        data = response,
                        error = null
                )
            }
            is StoreResponse.Error -> {
                copy(
                        error = response,
                        loading = null
                )
            }
            is StoreResponse.NoNewData -> {
                copy(
                        loading = null
                )
            }
        }
    }
}

class UIController<Key : Any, Output : Any>(
        val store: Store<Key, Output>,
        val request: StoreRequest<Key>
) {
    // use mutable state flow instead when we can
    private val refreshTrigger = ConflatedBroadcastChannel<Unit>().also {
        it.offer(Unit)
    }


    fun refresh() = refreshTrigger.offer(Unit)
    val state = flow<UIState<Output>> {
        var prevState : UIState<Output> = UIState()
        emitAll(refreshTrigger.asFlow().flatMapLatest {
            store.stream(request)
        }.map {
            prevState.combine(it).also {
                prevState = it
            }
            prevState
        })
    }
}