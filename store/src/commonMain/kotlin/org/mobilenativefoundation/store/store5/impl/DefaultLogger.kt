package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.Logger

/**
 * Default implementation of [Logger] using the Kermit logging library.
 *
 * Derives a tagged logger from Kermit's global instance rather than reconfiguring it, so the host
 * application's log writers and default tag are left untouched.
 */
internal class DefaultLogger : Logger {
    private val delegate = co.touchlab.kermit.Logger.withTag("Store")

    override fun debug(message: String) {
        delegate.d(message)
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        delegate.e(message, throwable)
    }
}
