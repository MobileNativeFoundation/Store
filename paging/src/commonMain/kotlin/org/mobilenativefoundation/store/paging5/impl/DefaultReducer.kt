package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.CustomActionReducer
import org.mobilenativefoundation.store.paging5.ErrorHandlingStrategy
import org.mobilenativefoundation.store.paging5.Logger
import org.mobilenativefoundation.store.paging5.MutablePagingBuffer
import org.mobilenativefoundation.store.paging5.PageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingConfig
import org.mobilenativefoundation.store.paging5.PagingReducer
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.PagingState
import org.mobilenativefoundation.store.paging5.QueueManager

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
class DefaultReducer<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CA : Any, CE : Any>(
    private val childScope: CoroutineScope,
    private val initialKey: CK,
    private val anchorPosition: StateFlow<Id?>,
    private val pagingConfig: PagingConfig,
    private val errorHandlingStrategy: ErrorHandlingStrategy,
    private val aggregatingStrategy: PageAggregatingStrategy<Id, CK, SO>,
    private val customActionReducer: CustomActionReducer<Id, CK, SO, CA, CE>?,
    internal val queueManager: QueueManager<Id, CK>,
    private val retriesRepository: RetriesRepository<Id, CK, SO>,
    private val mutablePagingBuffer: MutablePagingBuffer<Id, CK, SO>,
    private val logger: Logger?,
) : PagingReducer<Id, CK, SO, CE> {
    override fun reduce(
        state: PagingState<Id, CK, SO, CE>,
        action: PagingAction,
    ): PagingState<Id, CK, SO, CE> {
        logger?.d(
            """
            Reducing:
            Action: $action
            Previous state: $state
            """.trimIndent(),
        )

        return when (action) {
            is PagingAction.App.UpdateData<*, *, *> ->
                reduceUpdateDataAction(
                    state,
                    action as PagingAction.App.UpdateData<Id, CK, SO>,
                )

            is PagingAction.App.UpdateError<*, *, *> ->
                reduceUpdateErrorAction(
                    state,
                    action as PagingAction.App.UpdateError<Id, CK, CE>,
                )

            PagingAction.App.Start -> reduceStartAction()
            is PagingAction.User.Custom<*> -> reduceCustomAction(state, action as PagingAction.User.Custom<CA>)
            PagingAction.User.Refresh -> reduceRefreshAction(state)
            is PagingAction.App.Load<*, *> -> reduceLoadAction(state, action.key as CK)
            is PagingAction.User.Load<*, *> -> reduceLoadAction(state, action.key as CK)
        }
    }

    private fun reduceCustomAction(
        prevState: PagingState<Id, CK, SO, CE>,
        action: PagingAction.User.Custom<CA>,
    ): PagingState<Id, CK, SO, CE> {
        return customActionReducer?.reduce(prevState, action) ?: prevState
    }

    private fun reduceRefreshAction(prevState: PagingState<Id, CK, SO, CE>): PagingState<Id, CK, SO, CE> {
        return when (prevState) {
            is PagingState.Data -> {
                PagingState.Data.Refreshing(
                    data = prevState.data,
                    itemsBefore = prevState.itemsBefore,
                    itemsAfter = prevState.itemsAfter,
                    currentKey = initialKey,
                    nextKey = prevState.nextKey,
                    prefetchPosition = null,
                )
            }

            else -> {
                PagingState.Data.Refreshing(
                    data = emptyList(),
                    itemsBefore = null,
                    itemsAfter = null,
                    currentKey = initialKey,
                    nextKey = null,
                    prefetchPosition = null,
                )
            }
        }
    }

    private fun reduceStartAction(): PagingState<Id, CK, SO, CE> = PagingState.LoadingInitial(initialKey, null)

    private fun reduceLoadAction(
        prevState: PagingState<Id, CK, SO, CE>,
        key: CK,
    ): PagingState<Id, CK, SO, CE> {
        return when (prevState) {
            is PagingState.Data -> {
                PagingState.Data.LoadingMore(
                    data = prevState.data,
                    itemsBefore = prevState.itemsBefore,
                    itemsAfter = prevState.itemsAfter,
                    currentKey = prevState.currentKey,
                    nextKey = prevState.nextKey,
                    prefetchPosition = prevState.prefetchPosition,
                )
            }

            else -> {
                PagingState.Data.LoadingMore(
                    data = emptyList(),
                    itemsBefore = null,
                    itemsAfter = null,
                    currentKey = key,
                    nextKey = null,
                    prefetchPosition = prevState.prefetchPosition,
                )
            }
        }
    }

    private fun reduceUpdateDataAction(
        prevState: PagingState<Id, CK, SO, CE>,
        action: PagingAction.App.UpdateData<Id, CK, SO>,
    ): PagingState<Id, CK, SO, CE> {
        mutablePagingBuffer.put(action.params, action.page)

        val nextPagingItems =
            aggregatingStrategy.aggregate(
                anchorPosition = anchorPosition.value,
                prefetchPosition = prevState.prefetchPosition,
                pagingConfig = pagingConfig,
                pagingBuffer = mutablePagingBuffer,
            )

        resetRetriesFor(action.params)

        val nextPrefetchPosition = action.page.data.last().id

        return PagingState.Data.Idle(
            data = nextPagingItems.data,
            itemsBefore = action.page.itemsBefore,
            itemsAfter = action.page.itemsAfter,
            currentKey = action.page.prevKey,
            nextKey = action.page.nextKey,
            prefetchPosition = nextPrefetchPosition,
        )
    }

    private fun reduceUpdateErrorAction(
        prevState: PagingState<Id, CK, SO, CE>,
        action: PagingAction.App.UpdateError<Id, CK, CE>,
    ): PagingState<Id, CK, SO, CE> {
        return when (errorHandlingStrategy) {
            ErrorHandlingStrategy.Ignore -> {
                // Ignoring it
                prevState
            }

            ErrorHandlingStrategy.PassThrough -> {
                // Emitting it, but not doing anything else

                val errorState: PagingState.Error<Id, CK, SO, CE> =
                    when (action) {
                        is PagingAction.App.UpdateError.Custom ->
                            PagingState.Error.Custom(
                                action.error,
                                currentKey = prevState.currentKey,
                                prefetchPosition = prevState.prefetchPosition,
                            )

                        is PagingAction.App.UpdateError.Exception ->
                            PagingState.Error.Exception(
                                action.error,
                                currentKey = prevState.currentKey,
                                prefetchPosition = prevState.prefetchPosition,
                            )

                        is PagingAction.App.UpdateError.Message ->
                            PagingState.Error.Message(
                                action.error,
                                currentKey = prevState.currentKey,
                                prefetchPosition = prevState.prefetchPosition,
                            )
                    }

                if (prevState is PagingState.Data) {
                    PagingState.Data.ErrorLoadingMore(
                        error = errorState,
                        data = prevState.data,
                        itemsBefore = prevState.itemsBefore,
                        itemsAfter = prevState.itemsAfter,
                        currentKey = prevState.currentKey,
                        nextKey = prevState.nextKey,
                        prefetchPosition = prevState.prefetchPosition,
                    )
                } else {
                    errorState
                }
            }
        }
    }

    private fun resetRetriesFor(params: PagingSource.LoadParams<Id, CK>) {
        childScope.launch {
            retriesRepository.resetRetriesFor(params)
        }
    }
}
