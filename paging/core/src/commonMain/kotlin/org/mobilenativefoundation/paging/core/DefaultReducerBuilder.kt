package org.mobilenativefoundation.paging.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.paging.core.impl.DefaultAggregatingStrategy
import org.mobilenativefoundation.paging.core.impl.DefaultFetchingStrategy
import org.mobilenativefoundation.paging.core.impl.DefaultReducer
import org.mobilenativefoundation.paging.core.impl.Dispatcher
import org.mobilenativefoundation.paging.core.impl.Injector
import org.mobilenativefoundation.paging.core.impl.JobCoordinator
import org.mobilenativefoundation.paging.core.impl.OptionalInjector
import org.mobilenativefoundation.paging.core.impl.RetriesManager

/**
 * A builder class for creating a default [Reducer] instance.
 *
 * It enables configuring error handling strategy, aggregating strategy, fetching strategy, custom action reducer, and paging buffer size.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 * @param initialKey The initial [PagingKey] used as the starting point for paging.
 * @param childScope The [CoroutineScope] in which the reducer will operate.
 * @param dispatcherInjector The [Injector] used to provide the [Dispatcher] instance.
 * @param loggerInjector The [OptionalInjector] used to provide the optional [Logger] instance.
 * @param pagingConfigInjector The [Injector] used to provide the [PagingConfig] instance.
 * @param anchorPosition The [StateFlow] representing the anchor position for paging.
 */
class DefaultReducerBuilder<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> internal constructor(
    private val initialKey: PagingKey<K, P>,
    private val childScope: CoroutineScope,
    private val dispatcherInjector: Injector<Dispatcher<Id, K, P, D, E, A>>,
    private val loggerInjector: OptionalInjector<Logger>,
    private val pagingConfigInjector: Injector<PagingConfig>,
    private val anchorPosition: StateFlow<PagingKey<K, P>>,
    private val mutablePagingBufferInjector: Injector<MutablePagingBuffer<Id, K, P, D>>,
    private val jobCoordinator: JobCoordinator
) {

    private var errorHandlingStrategy: ErrorHandlingStrategy = ErrorHandlingStrategy.RetryLast()
    private var aggregatingStrategy: AggregatingStrategy<Id, K, P, D> = DefaultAggregatingStrategy()
    private var fetchingStrategy: FetchingStrategy<Id, K, P, D> = DefaultFetchingStrategy()
    private var customActionReducer: UserCustomActionReducer<Id, K, P, D, E, A>? = null

    /**
     * Sets the [ErrorHandlingStrategy] to be used by the reducer.
     *
     * @param errorHandlingStrategy The [ErrorHandlingStrategy] to be used.
     * @return The [DefaultReducerBuilder] instance for chaining.
     */
    fun errorHandlingStrategy(errorHandlingStrategy: ErrorHandlingStrategy) = apply { this.errorHandlingStrategy = errorHandlingStrategy }

    /**
     * Sets the [AggregatingStrategy] to be used by the reducer.
     *
     * @param aggregatingStrategy The [AggregatingStrategy] to be used.
     * @return The [DefaultReducerBuilder] instance for chaining.
     */
    fun aggregatingStrategy(aggregatingStrategy: AggregatingStrategy<Id, K, P, D>) = apply { this.aggregatingStrategy = aggregatingStrategy }

    /**
     * Sets the [FetchingStrategy] to be used by the reducer.
     *
     * @param fetchingStrategy The [FetchingStrategy] to be used.
     * @return The [DefaultReducerBuilder] instance for chaining.
     */
    fun fetchingStrategy(fetchingStrategy: FetchingStrategy<Id, K, P, D>) = apply { this.fetchingStrategy = fetchingStrategy }

    /**
     * Sets the custom action reducer to be used by the reducer.
     *
     * @param customActionReducer The [UserCustomActionReducer] to be used.
     * @return The [DefaultReducerBuilder] instance for chaining.
     */
    fun customActionReducer(customActionReducer: UserCustomActionReducer<Id, K, P, D, E, A>) = apply { this.customActionReducer = customActionReducer }

    /**
     * Builds and returns the configured default [Reducer] instance.
     *
     * @return The built default [Reducer] instance.
     */
    fun build(): Reducer<Id, K, P, D, E, A> {
        val mutablePagingBuffer = mutablePagingBufferInjector.inject()

        return DefaultReducer(
            childScope = childScope,
            dispatcherInjector = dispatcherInjector,
            pagingConfigInjector = pagingConfigInjector,
            userCustomActionReducer = customActionReducer,
            anchorPosition = anchorPosition,
            loggerInjector = loggerInjector,
            mutablePagingBuffer = mutablePagingBuffer,
            aggregatingStrategy = aggregatingStrategy,
            initialKey = initialKey,
            retriesManager = RetriesManager(),
            errorHandlingStrategy = errorHandlingStrategy,
            jobCoordinator = jobCoordinator
        )
    }
}
