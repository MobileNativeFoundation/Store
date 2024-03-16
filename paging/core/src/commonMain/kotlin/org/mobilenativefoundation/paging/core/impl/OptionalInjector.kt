package org.mobilenativefoundation.paging.core.impl

/**
 * An interface representing an optional injector for providing instances of a specific type.
 *
 * @param T The type of the instance to be injected.
 */
interface OptionalInjector<T : Any> {
    /**
     * Injects an instance of type [T] if available, or returns null.
     *
     * @return The injected instance or null if not available.
     */
    fun inject(): T?
}