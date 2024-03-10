package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingState
import org.mobilenativefoundation.store.paging5.PostReducerEffect
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
class PostReducerEffectHolder<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any> {
    private val effects:
        MutableMap<
            KClass<out PagingState<Id, CK, SO, CE>>,
            MutableMap<KClass<out PagingAction>, MutableList<PostReducerEffect<Id, CK, SO, CE, *, *>>>,
            > = mutableMapOf()

    fun <S : PagingState<Id, CK, SO, CE>, A : PagingAction> get(
        state: KClass<out PagingState<*, *, *, *>>,
        action: KClass<out PagingAction>,
    ): List<PostReducerEffect<Id, CK, SO, CE, S, A>> {
        return effects[state as KClass<S>]?.get(action) as? List<PostReducerEffect<Id, CK, SO, CE, S, A>>
            ?: emptyList()
    }

    fun <S : PagingState<Id, CK, SO, CE>, A : PagingAction> put(
        state: KClass<out PagingState<*, *, *, *>>,
        action: KClass<out PagingAction>,
        effect: PostReducerEffect<Id, CK, SO, CE, S, A>,
    ) {
        val castedState = state as KClass<out S>

        if (castedState !in effects) {
            effects[castedState] = mutableMapOf()
        }

        if (action !in effects[state]!!) {
            effects[state]!![action] = mutableListOf()
        }

        effects[state]!![action]!!.add(effect)
    }
}
