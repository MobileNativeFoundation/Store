package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.CoroutineScope
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.Dispatcher
import org.mobilenativefoundation.store.paging5.Logger
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingMiddleware
import org.mobilenativefoundation.store.paging5.PagingReducer
import org.mobilenativefoundation.store.paging5.PagingState
import org.mobilenativefoundation.store.paging5.PagingStateManager

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
class RealDispatcher<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CA : Any, CE : Any>(
    private val middlewares: List<PagingMiddleware<Id, CK, SO, CA, CE>>,
    private val pagingStateManager: PagingStateManager<Id, CK, SO, CE>,
    private val reducer: PagingReducer<Id, CK, SO, CE>,
    private val logger: Logger?,
    private val postReducerEffectHolder: PostReducerEffectHolder<Id, CK, SO, CE>,
    private val childScope: CoroutineScope,
) : Dispatcher {
    override fun <A : PagingAction> dispatch(
        action: A,
        index: Int,
    ) {
        if (index < middlewares.size) {
            middlewares[index].apply(action) { nextAction ->
                dispatch(nextAction, index + 1)
            }
        } else {
            val prevState = pagingStateManager.state.value
            val nextState = reducer.reduce(prevState, action)

            logger?.d(
                """
                Updating state:
                Prev state: $prevState
                Next state: $nextState
                """.trimIndent(),
            )

            pagingStateManager.updateState(nextState)

            when (nextState) {
                is PagingState.Data.ErrorLoadingMore<*, *, *, *> -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Data.ErrorLoadingMore<Id, CK, SO, CE>, A>(
                            nextState::class,
                            action::class,
                        )

                    nextState as PagingState.Data.ErrorLoadingMore<Id, CK, SO, CE>

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.Data.Idle -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Data.Idle<Id, CK, SO>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.Data.LoadingMore -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Data.LoadingMore<Id, CK, SO>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.Data.Refreshing -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Data.Refreshing<Id, CK, SO>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.Error.Custom -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Error.Custom<Id, CK, SO, CE>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.Error.Exception -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Error.Exception<Id, CK, SO, CE>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.Error.Message -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Error.Message<Id, CK, SO, CE>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.Initial -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.Initial<Id, CK, SO>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach { it.run(nextState, action, dispatch = ::dispatch) }
                }

                is PagingState.LoadingInitial -> {
                    val reducerEffects =
                        postReducerEffectHolder.get<PagingState.LoadingInitial<Id, CK, SO>, A>(
                            nextState::class,
                            action::class,
                        )

                    reducerEffects.forEach {
                        it.run(nextState, action, dispatch = ::dispatch)
                    }
                }
            }
        }
    }
}
