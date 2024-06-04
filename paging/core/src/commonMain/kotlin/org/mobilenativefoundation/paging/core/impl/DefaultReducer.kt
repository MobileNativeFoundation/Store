package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.mobilenativefoundation.paging.core.AggregatingStrategy
import org.mobilenativefoundation.paging.core.ErrorHandlingStrategy
import org.mobilenativefoundation.paging.core.Logger
import org.mobilenativefoundation.paging.core.MutablePagingBuffer
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingConfig
import org.mobilenativefoundation.paging.core.PagingKey
import org.mobilenativefoundation.paging.core.PagingSource
import org.mobilenativefoundation.paging.core.PagingState
import org.mobilenativefoundation.paging.core.Reducer
import org.mobilenativefoundation.paging.core.UserCustomActionReducer

class DefaultReducer<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    private val childScope: CoroutineScope,
    private val initialKey: PagingKey<K, P>,
    private val dispatcherInjector: Injector<Dispatcher<Id, K, P, D, E, A>>,
    pagingConfigInjector: Injector<PagingConfig>,
    private val userCustomActionReducer: UserCustomActionReducer<Id, K, P, D, E, A>?,
    private val anchorPosition: StateFlow<PagingKey<K, P>>,
    loggerInjector: OptionalInjector<Logger>,
    private val errorHandlingStrategy: ErrorHandlingStrategy,
    private val mutablePagingBuffer: MutablePagingBuffer<Id, K, P, D>,
    private val aggregatingStrategy: AggregatingStrategy<Id, K, P, D>,
    private val retriesManager: RetriesManager<Id, K, P, D>,
    private val jobCoordinator: JobCoordinator,
) : Reducer<Id, K, P, D, E, A> {

    private val logger = lazy { loggerInjector.inject() }
    private val pagingConfig = lazy { pagingConfigInjector.inject() }
    private val dispatcher = lazy { dispatcherInjector.inject() }

    override suspend fun reduce(action: PagingAction<Id, K, P, D, E, A>, state: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        logger.value?.log(
            """
            Reducing:
                Action: $action
                Previous state: $state
            """.trimIndent(),
        )

        return when (action) {
            is PagingAction.UpdateData -> reduceUpdateDataAction(action, state)
            is PagingAction.User.Custom -> reduceUserCustomAction(action, state)
            is PagingAction.User.Load -> reduceUserLoadAction(action, state)
            is PagingAction.UpdateError -> reduceUpdateErrorAction(action, state)
            is PagingAction.Load -> reduceLoadAction(action, state)
        }
    }

    private fun reduceUpdateDataAction(action: PagingAction.UpdateData<Id, K, P, D, E, A>, prevState: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        mutablePagingBuffer.put(action.params, action.data)

        val nextPagingItems = aggregatingStrategy.aggregate(
            anchorPosition = anchorPosition.value,
            prefetchPosition = action.params.key,
            pagingConfig = pagingConfig.value,
            pagingBuffer = mutablePagingBuffer
        )

        resetRetriesFor(action.params)

        return PagingState.Data.Idle(
            data = nextPagingItems.data,
            itemsBefore = action.data.collection.itemsBefore,
            itemsAfter = action.data.collection.itemsAfter,
            currentKey = action.data.collection.prevKey,
            nextKey = action.data.collection.nextKey,
            prefetchPosition = action.params.key
        )

    }

    private suspend fun reduceUpdateErrorAction(action: PagingAction.UpdateError<Id, K, P, D, E, A>, prevState: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        return when (errorHandlingStrategy) {
            ErrorHandlingStrategy.Ignore -> prevState
            ErrorHandlingStrategy.PassThrough -> reduceUpdateErrorActionWithPassThrough(action, prevState)
            is ErrorHandlingStrategy.RetryLast -> retryLast(errorHandlingStrategy.maxRetries, action, prevState)
        }
    }

    private suspend fun retryLast(maxRetries: Int, action: PagingAction.UpdateError<Id, K, P, D, E, A>, prevState: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {

        val retries = retriesManager.getRetriesFor(action.params)

        return if (retries < maxRetries) {
            // Retry without emitting the error

            jobCoordinator.cancel(action.params.key)
            retriesManager.incrementRetriesFor(action.params)
            dispatcher.value.dispatch(PagingAction.Load(action.params.key))
            prevState
        } else {
            // Emit the error and reset the counter

            retriesManager.resetRetriesFor(action.params)
            if (prevState is PagingState.Data) {
                PagingState.Data.ErrorLoadingMore(
                    error = action.error,
                    data = prevState.data,
                    itemsBefore = prevState.itemsBefore,
                    itemsAfter = prevState.itemsAfter,
                    nextKey = prevState.nextKey,
                    currentKey = prevState.currentKey,
                    prefetchPosition = prevState.prefetchPosition
                )
            } else {
                when (action.error) {
                    is PagingSource.LoadResult.Error.Custom -> PagingState.Error.Custom(action.error.error, action.params.key, prevState.prefetchPosition)
                    is PagingSource.LoadResult.Error.Exception -> PagingState.Error.Exception(action.error.error, action.params.key, prevState.prefetchPosition)
                }
            }
        }
    }

    private fun reduceUpdateErrorActionWithPassThrough(action: PagingAction.UpdateError<Id, K, P, D, E, A>, prevState: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        // Emitting it, but not doing anything else

        val errorState: PagingState.Error<Id, K, P, D, E, *> = when (action.error) {
            is PagingSource.LoadResult.Error.Custom -> PagingState.Error.Custom(action.error.error, action.params.key, prevState.prefetchPosition)
            is PagingSource.LoadResult.Error.Exception -> PagingState.Error.Exception(action.error.error, action.params.key, prevState.prefetchPosition)
        }

        return if (prevState is PagingState.Data) {
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


    private fun reduceUserCustomAction(action: PagingAction.User.Custom<Id, K, P, D, E, A>, prevState: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        return userCustomActionReducer?.reduce(action, prevState) ?: prevState
    }

    private fun reduceLoadActionAndDataState(prevState: PagingState.Data<Id, K, P, D, E>) = PagingState.Data.LoadingMore<Id, K, P, D, E>(
        data = prevState.data,
        itemsBefore = prevState.itemsBefore,
        itemsAfter = prevState.itemsAfter,
        currentKey = prevState.currentKey,
        nextKey = prevState.nextKey,
        prefetchPosition = prevState.prefetchPosition
    )

    private fun reduceLoadActionAndNonDataState(key: PagingKey<K, P>, prevState: PagingState<Id, K, P, D, E>) = PagingState.Loading<Id, K, P, D, E>(
        currentKey = key,
        prefetchPosition = prevState.prefetchPosition
    )

    private fun reduceLoadAction(action: PagingAction.Load<Id, K, P, D, E, A>, prevState: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        return if (prevState is PagingState.Data) reduceLoadActionAndDataState(prevState) else reduceLoadActionAndNonDataState(action.key, prevState)
    }


    private fun reduceUserLoadAction(action: PagingAction.User.Load<Id, K, P, D, E, A>, prevState: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        return if (prevState is PagingState.Data) reduceLoadActionAndDataState(prevState) else reduceLoadActionAndNonDataState(action.key, prevState)
    }

    private fun resetRetriesFor(params: PagingSource.LoadParams<K, P>) {
        childScope.launch {
            retriesManager.resetRetriesFor(params)
        }
    }
}