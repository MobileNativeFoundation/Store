package org.mobilenativefoundation.paging.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.plus
import org.mobilenativefoundation.paging.core.PagingConfig.InsertionStrategy
import org.mobilenativefoundation.paging.core.impl.DefaultAppLoadEffect
import org.mobilenativefoundation.paging.core.impl.DefaultFetchingStrategy
import org.mobilenativefoundation.paging.core.impl.DefaultLoadNextEffect
import org.mobilenativefoundation.paging.core.impl.DefaultLogger
import org.mobilenativefoundation.paging.core.impl.DefaultPagingSource
import org.mobilenativefoundation.paging.core.impl.DefaultPagingSourceCollector
import org.mobilenativefoundation.paging.core.impl.DefaultUserLoadEffect
import org.mobilenativefoundation.paging.core.impl.DefaultUserLoadMoreEffect
import org.mobilenativefoundation.paging.core.impl.Dispatcher
import org.mobilenativefoundation.paging.core.impl.EffectsHolder
import org.mobilenativefoundation.paging.core.impl.EffectsLauncher
import org.mobilenativefoundation.paging.core.impl.QueueManager
import org.mobilenativefoundation.paging.core.impl.RealDispatcher
import org.mobilenativefoundation.paging.core.impl.RealInjector
import org.mobilenativefoundation.paging.core.impl.RealJobCoordinator
import org.mobilenativefoundation.paging.core.impl.RealMutablePagingBuffer
import org.mobilenativefoundation.paging.core.impl.RealOptionalInjector
import org.mobilenativefoundation.paging.core.impl.RealPager
import org.mobilenativefoundation.paging.core.impl.RealQueueManager
import org.mobilenativefoundation.paging.core.impl.StateManager
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import kotlin.reflect.KClass


/**
 * A builder class for creating a [Pager] instance.
 * The [PagerBuilder] enables configuring the paging behavior,
 * such as the initial state, initial key, anchor position, middleware, effects, reducer, logger, and paging config.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched.
 * @param scope The [CoroutineScope] in which the paging operations will be performed.
 * @param initialState The initial [PagingState] of the pager.
 * @param initialKey The initial [PagingKey] of the pager.
 * @param anchorPosition A [StateFlow] representing the anchor position for paging.
 */
class PagerBuilder<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    scope: CoroutineScope,
    initialState: PagingState<Id, K, P, D, E>,
    private val initialKey: PagingKey<K, P>,
    private val anchorPosition: StateFlow<PagingKey<K, P>>
) {

    private val childScope = scope + Job()
    private val jobCoordinator = RealJobCoordinator(childScope)

    private var middleware: MutableList<Middleware<Id, K, P, D, E, A>> = mutableListOf()

    private var pagingConfigInjector = RealInjector<PagingConfig>().apply {
        instance = PagingConfig(10, 50, InsertionStrategy.APPEND)
    }

    private var fetchingStrategyInjector = RealInjector<FetchingStrategy<Id, K, P, D>>().apply {
        this.instance = DefaultFetchingStrategy()
    }

    private var pagingBufferMaxSize = 100

    private val effectsHolder: EffectsHolder<Id, K, P, D, E, A> = EffectsHolder()

    private val dispatcherInjector = RealInjector<Dispatcher<Id, K, P, D, E, A>>()

    private val loggerInjector = RealOptionalInjector<Logger>()

    private val queueManagerInjector = RealInjector<QueueManager<K, P>>()
    private val mutablePagingBufferInjector = RealInjector<MutablePagingBuffer<Id, K, P, D>>().apply {
        this.instance = mutablePagingBufferOf<Id, K, P, D, E, A>(500)
    }

    private val insertionStrategyInjector = RealInjector<InsertionStrategy>()
    private val pagingSourceCollectorInjector = RealInjector<PagingSourceCollector<Id, K, P, D, E, A>>().apply {
        this.instance = DefaultPagingSourceCollector()
    }
    private val pagingSourceInjector = RealInjector<PagingSource<Id, K, P, D, E>>()

    private val stateManager = StateManager(initialState, loggerInjector)

    private var loadNextEffect: LoadNextEffect<Id, K, P, D, E, A> = DefaultLoadNextEffect(loggerInjector, queueManagerInjector)

    private var appLoadEffect: AppLoadEffect<Id, K, P, D, E, A> = DefaultAppLoadEffect(
        loggerInjector = loggerInjector,
        dispatcherInjector = dispatcherInjector,
        jobCoordinator = jobCoordinator,
        pagingSourceCollectorInjector = pagingSourceCollectorInjector,
        pagingSourceInjector = pagingSourceInjector,
        stateManager = stateManager
    )

    private var userLoadEffect: UserLoadEffect<Id, K, P, D, E, A> = DefaultUserLoadEffect(
        loggerInjector = loggerInjector,
        dispatcherInjector = dispatcherInjector,
        jobCoordinator = jobCoordinator,
        pagingSourceCollectorInjector = pagingSourceCollectorInjector,
        pagingSourceInjector = pagingSourceInjector,
        stateManager = stateManager
    )

    private var userLoadMoreEffect: UserLoadMoreEffect<Id, K, P, D, E, A> = DefaultUserLoadMoreEffect(
        loggerInjector = loggerInjector,
        dispatcherInjector = dispatcherInjector,
        jobCoordinator = jobCoordinator,
        pagingSourceCollectorInjector = pagingSourceCollectorInjector,
        pagingSourceInjector = pagingSourceInjector,
        stateManager = stateManager
    )

    private lateinit var reducer: Reducer<Id, K, P, D, E, A>

    /**
     * Sets the [Reducer] for the pager.
     *
     * @param reducer The [Reducer] to be used for reducing paging actions and state.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun reducer(reducer: Reducer<Id, K, P, D, E, A>) = apply { this.reducer = reducer }

    /**
     * Configures the default [Reducer] using the provided [DefaultReducerBuilder].
     *
     * @param block A lambda function that takes a [DefaultReducerBuilder] as receiver and allows configuring the default reducer.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun defaultReducer(
        block: DefaultReducerBuilder<Id, K, P, D, E, A>.() -> Unit
    ) = apply {
        val builder = DefaultReducerBuilder<Id, K, P, D, E, A>(
            childScope = childScope,
            initialKey = initialKey,
            dispatcherInjector = dispatcherInjector,
            loggerInjector = loggerInjector,
            pagingConfigInjector = pagingConfigInjector,
            anchorPosition = anchorPosition,
            mutablePagingBufferInjector = mutablePagingBufferInjector,
            jobCoordinator = jobCoordinator
        )
        block(builder)
        val reducer = builder.build()
        this.reducer = reducer
    }

    /**
     * Adds an [Effect] to be invoked after reducing the state for the specified [PagingAction] and [PagingState] types.
     *
     * @param PA The type of the [PagingAction] that triggers the effect.
     * @param S The type of the [PagingState] that triggers the effect.
     * @param action The [KClass] of the [PagingAction] that triggers the effect.
     * @param state The [KClass] of the [PagingState] that triggers the effect.
     * @param effect The [Effect] to be invoked.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun <PA : PagingAction<Id, K, P, D, E, A>, S : PagingState<Id, K, P, D, E>> effect(
        action: KClass<out PagingAction<*, *, *, *, *, *>>,
        state: KClass<out PagingState<*, *, *, *, *>>,
        effect: Effect<Id, K, P, D, E, A, PA, S>
    ) = apply {
        this.effectsHolder.put(action, state, effect)
    }

    /**
     * Sets the [LoadNextEffect] for the pager.
     *
     * @param effect The [LoadNextEffect] to be used for loading the next page of data.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun loadNextEffect(effect: LoadNextEffect<Id, K, P, D, E, A>) = apply { this.loadNextEffect = effect }

    fun appLoadEffect(effect: AppLoadEffect<Id, K, P, D, E, A>) = apply { this.appLoadEffect = effect }
    fun userLoadEffect(effect: UserLoadEffect<Id, K, P, D, E, A>) = apply { this.userLoadEffect = effect }
    fun userLoadMoreEffect(effect: UserLoadMoreEffect<Id, K, P, D, E, A>) = apply { this.userLoadMoreEffect = effect }

    /**
     * Adds a [Middleware] to the pager.
     *
     * @param middleware The [Middleware] to be added.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun middleware(middleware: Middleware<Id, K, P, D, E, A>) = apply {
        this.middleware.add(middleware)
    }

    /**
     * Sets the [Logger] for the pager.
     *
     * @param logger The [Logger] to be used for logging.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun logger(logger: Logger) = apply { this.loggerInjector.instance = logger }

    /**
     * Sets the default [Logger] for the pager.
     *
     * @return The [PagerBuilder] instance for chaining.
     */
    fun defaultLogger() = apply { this.loggerInjector.instance = DefaultLogger() }

    /**
     * Sets the [PagingConfig] for the pager.
     *
     * @param pagingConfig The [PagingConfig] to be used for configuring the paging behavior.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun pagingConfig(pagingConfig: PagingConfig) = apply { this.pagingConfigInjector.instance = pagingConfig }

    /**
     * Sets the maximum size of the pager buffer.
     *
     * @param maxSize The maximum size of the pager buffer.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun pagerBufferMaxSize(maxSize: Int) = apply { this.mutablePagingBufferInjector.instance = RealMutablePagingBuffer<Id, K, P, D, E, A>(maxSize) }

    /**
     * Sets the [InsertionStrategy] for the pager.
     *
     * @param insertionStrategy The [InsertionStrategy] to be used for inserting new data into the pager buffer.
     * @return The [PagerBuilder] instance for chaining.
     */
    fun insertionStrategy(insertionStrategy: InsertionStrategy) = apply { this.insertionStrategyInjector.instance = insertionStrategy }

    fun pagingSourceCollector(pagingSourceCollector: PagingSourceCollector<Id, K, P, D, E, A>) = apply { this.pagingSourceCollectorInjector.instance = pagingSourceCollector }

    fun pagingSource(pagingSource: PagingSource<Id, K, P, D, E>) = apply { this.pagingSourceInjector.instance = pagingSource }

    fun defaultPagingSource(streamProvider: PagingSourceStreamProvider<Id, K, P, D, E>) = apply {
        this.pagingSourceInjector.instance = DefaultPagingSource<Id, K, P, D, E, A>(streamProvider)
    }

    @OptIn(ExperimentalStoreApi::class)
    fun mutableStorePagingSource(store: MutableStore<PagingKey<K, P>, PagingData<Id, K, P, D>>, factory: () -> StorePagingSourceKeyFactory<Id, K, P, D>) = apply {
        this.pagingSourceInjector.instance = DefaultPagingSource<Id, K, P, D, E, A>(
            streamProvider = store.pagingSourceStreamProvider<Id, K, P, D, E, A>(
                keyFactory = factory()
            )
        )
    }

    fun storePagingSource(store: Store<PagingKey<K, P>, PagingData<Id, K, P, D>>, factory: () -> StorePagingSourceKeyFactory<Id, K, P, D>) = apply {
        this.pagingSourceInjector.instance = DefaultPagingSource<Id, K, P, D, E, A>(
            streamProvider = store.pagingSourceStreamProvider<Id, K, P, D, E, A>(
                keyFactory = factory()
            )
        )
    }

    private fun provideDefaultEffects() {
        this.effectsHolder.put(PagingAction.UpdateData::class, PagingState.Data.Idle::class, this.loadNextEffect)
        this.effectsHolder.put(PagingAction.Load::class, PagingState::class, this.appLoadEffect)
        this.effectsHolder.put(PagingAction.User.Load::class, PagingState.Loading::class, this.userLoadEffect)
        this.effectsHolder.put(PagingAction.User.Load::class, PagingState.Data.LoadingMore::class, this.userLoadMoreEffect)
    }

    private fun provideDispatcher() {
        val effectsLauncher = EffectsLauncher<Id, K, P, D, E, A>(effectsHolder)

        val dispatcher = RealDispatcher(
            stateManager = stateManager,
            middleware = middleware,
            reducer = reducer,
            effectsLauncher = effectsLauncher,
            childScope = childScope
        )

        dispatcherInjector.instance = dispatcher
    }

    private fun provideQueueManager() {
        val queueManager = RealQueueManager<Id, K, P, D, E, A>(
            pagingConfigInjector = pagingConfigInjector,
            loggerInjector = loggerInjector,
            dispatcherInjector = dispatcherInjector,
            fetchingStrategy = fetchingStrategyInjector.inject(),
            pagingBuffer = mutablePagingBufferInjector.inject(),
            anchorPosition = anchorPosition,
            stateManager = stateManager
        )

        queueManagerInjector.instance = queueManager
    }

    /**
     * Builds and returns the [Pager] instance.
     *
     * @return The created [Pager] instance.
     */
    fun build(): Pager<Id, K, P, D, E, A> {

        provideDefaultEffects()
        provideDispatcher()
        provideQueueManager()

        return RealPager(
            initialKey = initialKey,
            dispatcher = dispatcherInjector.inject(),
            pagingConfigInjector = pagingConfigInjector,
            stateManager = stateManager,
        )
    }
}