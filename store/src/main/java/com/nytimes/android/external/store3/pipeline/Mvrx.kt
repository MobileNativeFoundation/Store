package com.nytimes.android.external.store3.pipeline

import com.airbnb.mvrx.RealStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1


internal data class MvRxTuple1<A>(val a: A)
internal data class MvRxTuple2<A, B>(val a: A, val b: B)
internal data class MvRxTuple3<A, B, C>(val a: A, val b: B, val c: C)
internal data class MvRxTuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
internal data class MvRxTuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
internal data class MvRxTuple6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
internal data class MvRxTuple7<A, B, C, D, E, F, G>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F, val g: G)

interface StateStore<S : Any> : CoroutineScope {
    val state: S
    fun get(block: (S) -> Unit)
    fun set(stateReducer: S.() -> S)
    val observable: Flow<S>
}

interface MvRxState {

}


/**
 * Checks that a state's value is not changed over its lifetime.
 */
internal class MutableStateChecker<S : MvRxState>(val initialState: S) {

    data class StateWrapper<S : MvRxState>(val state: S) {
        private val originalHashCode = hashCode()

        fun validate() = require(originalHashCode == hashCode()) {
            "${state::class.java.simpleName} was mutated. State classes should be immutable."
        }
    }

    private var previousState = StateWrapper(initialState)

    /**
     * Should be called whenever state changes. This validates that the hashcode of each state
     * instance does not change between when it is first set and when the next state is set.
     * If it does change it means different state instances share some mutable data structure.
     */
    fun onStateChanged(newState: S) {
        previousState.validate()
        previousState = StateWrapper(newState)
    }
}

open class StateProcessor<S : MvRxState>(
        initialState: S,
        private val stateStore: StateStore<S> = RealStateStore(initialState)
) {

    private val tag by lazy { javaClass.simpleName }
    private val lastDeliveredStates = ConcurrentHashMap<String, Any>()
    private val activeSubscriptions = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    internal val state: S
        get() = stateStore.state


    /**
     * Call this to mutate the current state.
     * A few important notes about the state reducer.
     * 1) It will not be called synchronously or on the same thread. This is for performance and accuracy reasons.
     * 2) Similar to the execute lambda above, the current state is the state receiver so the `count` in `count + 1` is actually the count
     *    property of the state at the time that the lambda is called.
     * 3) In development, MvRx will do checks to make sure that your setState is pure by calling in multiple times. As a result, DO NOT use
     *    mutable variables or properties from outside the lambda or else it may crash.
     */
    protected fun setState(reducer: S.() -> S) {
        stateStore.set(reducer)
    }

    /**
     * Access the current ViewModel state. Takes a block of code that will be run after all current pending state
     * updates are processed.
     */
    protected fun withState(block: (state: S) -> Unit) {
        stateStore.get(block)
    }


    /**
     * Helper to map a [Single] to an [Async] property on the state object.
     */
    fun <T> Flow<T>.execute(stateReducer: S.(Async<T>) -> S) = execute({ it }, null, stateReducer)


    /**
     * Execute an [Observable] and wrap its progression with [Async] property reduced to the global state.
     *
     * @param mapper A map converting the Observable type to the desired Async type.
     * @param successMetaData A map that provides metadata to set on the Success result.
     *                        It allows data about the original Observable to be kept and accessed later. For example,
     *                        your mapper could map a network request to just the data your UI needs, but your base layers could
     *                        keep metadata about the request, like timing, for logging.
     * @param stateReducer A reducer that is applied to the current state and should return the
     *                     new state. Because the state is the receiver and it likely a data
     *                     class, an implementation may look like: `{ copy(response = it) }`.
     *
     *  @see Success.metadata
     */
    fun <T, V> Flow<T>.execute(
            mapper: (T) -> V,
            successMetaData: ((T) -> Any)? = null,
            stateReducer: S.(Async<V>) -> S
    ) {
        setState { stateReducer(Loading()) }
        val flow: Flow<Async<V>> = map { value ->
            val success = Success(mapper(value))
            success.metadata = successMetaData?.invoke(value)
            success as Async<V>
        }
                .catch { emit(Fail(it)) } //mapping the error and re-emitting

        stateStore.launch {
            flow.collect {
                setState { stateReducer(it) }
            }
        }


    }


    /**
     * For ViewModels that want to subscribe to itself.
     */
    protected fun subscribe(subscriber: (S) -> Unit) {
        stateStore.launch {
            stateStore.observable.collect { subscriber.invoke(it) }
        }

    }


    /**
     * Subscribe to state changes for only a single property.
     */
    protected fun <A> selectSubscribe(
            prop1: KProperty1<S, A>,
            coroutineScope: CoroutineScope,
            subscriber: (A) -> Unit
    ) = selectSubscribeInternal(prop1,coroutineScope, subscriber)


    private fun <A> selectSubscribeInternal(
            prop1: KProperty1<S, A>,
            scope: CoroutineScope,
            subscriber: (A) -> Unit
    ) {
        val flow = stateStore.observable
                .map { MvRxTuple1(prop1.get(it)) }
                .distinctUntilChanged()
        scope.launch {
            flow.collect { subscriber.invoke(it.a) }
        }
    }

    /**
     * Subscribe to changes in an async property. There are optional parameters for onSuccess
     * and onFail which automatically unwrap the value or error.
     */
    protected fun <T> asyncSubscribe(
            asyncProp: KProperty1<S, Async<T>>,
            coroutineScope: CoroutineScope,
            onFail: ((Throwable) -> Unit)? = null,
            onSuccess: ((T) -> Unit)? = null
           ) =
            asyncSubscribeInternal(asyncProp, coroutineScope, onFail, onSuccess)


    private fun <T> asyncSubscribeInternal(
            asyncProp: KProperty1<S, Async<T>>,
            coroutineScope: CoroutineScope,
            onFail: ((Throwable) -> Unit)? = null,
            onSuccess: ((T) -> Unit)? = null

    ) = selectSubscribeInternal(asyncProp,  coroutineScope) { asyncValue ->
        if (onSuccess != null && asyncValue is Success) {
            onSuccess(asyncValue())
        } else if (onFail != null && asyncValue is Fail) {
            onFail(asyncValue.error)
        }
    }


    override fun toString(): String = "${this::class.simpleName} $state"


    /**
     * Defines what updates a subscription should receive.
     * See: [RedeliverOnStart], [UniqueOnly].
     */
    sealed class DeliveryMode {
        internal fun appendPropertiesToId(vararg properties: KProperty1<*, *>): DeliveryMode {
            return when (this) {
                is RedeliverOnStart -> RedeliverOnStart
                is UniqueOnly -> UniqueOnly(subscriptionId + "_" + properties.joinToString(",") { it.name })
                else -> RedeliverOnStart
            }
        }


        /**
         * The subscription will receive the most recent state update when transitioning from locked to unlocked states (stopped -> started),
         * even if the state has not changed while locked.
         *
         * Likewise, when a MvRxView resubscribes after a configuration change the most recent update will always be emitted.
         */
        object RedeliverOnStart : DeliveryMode()

        /**
         * The subscription will receive the most recent state update when transitioning from locked to unlocked states (stopped -> started),
         * only if the state has changed while locked.
         *
         * Likewise, when a MvRxView resubscribes after a configuration change the most recent update will only be emitted
         * if the state has changed while locked.
         *
         * @param subscriptionId A uniqueIdentifier for this subscription. It is an error for two unique only subscriptions to
         * have the same id.
         */
        class UniqueOnly(val subscriptionId: String) : DeliveryMode()
    }
}