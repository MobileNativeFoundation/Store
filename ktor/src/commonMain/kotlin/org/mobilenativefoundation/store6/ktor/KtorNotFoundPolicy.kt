package org.mobilenativefoundation.store6.ktor

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** Whether a 404/410 clears the resident value or surfaces a typed error. */
@ExperimentalStoreApi
public enum class KtorNotFoundPolicy { Error, Delete }
