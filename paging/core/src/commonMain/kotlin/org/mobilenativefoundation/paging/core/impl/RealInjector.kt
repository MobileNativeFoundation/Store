package org.mobilenativefoundation.paging.core.impl

internal class RealInjector<T : Any> : Injector<T> {
    var instance: T? = null

    override fun inject(): T {
        return instance!!
    }
}