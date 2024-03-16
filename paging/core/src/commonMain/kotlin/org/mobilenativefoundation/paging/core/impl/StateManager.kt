package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mobilenativefoundation.paging.core.Logger
import org.mobilenativefoundation.paging.core.PagingState

/**
 * A class for managing the state of the paging process.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param initialState The initial [PagingState].
 * @param loggerInjector The [OptionalInjector] for providing a [Logger] instance.
 */
class StateManager<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
    initialState: PagingState<Id, K, P, D, E>,
    loggerInjector: OptionalInjector<Logger>
) {

    private val logger = lazy { loggerInjector.inject() }

    private val _state = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    /**
     * Updates the state with the specified [PagingState].
     *
     * @param nextState The next [PagingState] to update the state with.
     */
    fun update(nextState: PagingState<Id, K, P, D, E>) {

        log(nextState)

        _state.value = nextState
    }

    private fun log(nextState: PagingState<Id, K, P, D, E>) {
        logger.value?.log(
            """
            Updating state:
                Previous state: ${_state.value}
                Next state: $nextState
        """.trimIndent()
        )
    }
}