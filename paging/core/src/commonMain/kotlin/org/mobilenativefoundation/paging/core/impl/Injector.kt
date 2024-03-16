package org.mobilenativefoundation.paging.core.impl

/**
 * An interface representing an injector for providing instances of a specific type.
 *
 * @param T The type of the instance to be injected.
 */
interface Injector<T : Any> {

    /**
     * Injects an instance of type [T].
     *
     * @return The injected instance.
     */
    fun inject(): T
}