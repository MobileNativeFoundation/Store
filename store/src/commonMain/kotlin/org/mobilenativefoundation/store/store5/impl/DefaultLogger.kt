package org.mobilenativefoundation.store.store5.impl

import co.touchlab.kermit.CommonWriter
import org.mobilenativefoundation.store.store5.Logger

/**
 * Default implementation of [Logger] using the Kermit logging library.
 */
internal class DefaultLogger : Logger {

    private val delegate =
        co.touchlab.kermit.Logger.apply {
            setLogWriters(listOf(CommonWriter()))
            setTag("Store")
        }

    override fun debug(message: String) {
        delegate.d(message)
    }

    override fun error(message: String, throwable: Throwable?) {
        delegate.e(message, throwable)
    }
}