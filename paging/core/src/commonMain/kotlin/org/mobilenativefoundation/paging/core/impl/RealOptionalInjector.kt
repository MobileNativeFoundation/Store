package org.mobilenativefoundation.paging.core.impl

class RealOptionalInjector<T : Any> : OptionalInjector<T> {
    var instance: T? = null

    override fun inject(): T? {
        return instance
    }
}