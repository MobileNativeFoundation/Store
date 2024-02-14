package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

/**
 * The [DispatcherInjector] interface serves as a bridge to inject the actual [Dispatcher] instance
 * into the default middleware without creating a dependency cycle.
 *
 * In the process of building a [Pager], the default middleware needs access to the [Dispatcher]
 * instance to dispatch actions. However, the [Dispatcher] itself is created using the
 * [DispatcherBuilder], which requires the middleware to be configured before building the
 * [Dispatcher].
 *
 * To break this dependency cycle, the [DispatcherInjector] acts as an intermediary. It holds a
 * reference to the [Dispatcher.dispatch] function, which is initially empty. The
 * [DispatcherInjector] is passed to the [DispatcherBuilder] during the configuration phase, allowing
 * the default middleware to be set up using the [DispatcherInjector].
 *
 * After the [Dispatcher] is built, the [dispatch] function of the [DispatcherInjector] is updated
 * to invoke the actual [Dispatcher.dispatch] function. This ensures that the default
 * middleware can dispatch actions using the actual [Dispatcher] instance, even though it was
 * configured before the [Dispatcher] was created.
 *
 * By using the [DispatcherInjector], the dependency cycle between the [Dispatcher] and the default
 * middleware is broken, enabling a clean and flexible configuration process for building the [Pager].
 */
@ExperimentalStoreApi
interface DispatcherInjector {
    var dispatch: (action: PagingAction) -> Unit
}
