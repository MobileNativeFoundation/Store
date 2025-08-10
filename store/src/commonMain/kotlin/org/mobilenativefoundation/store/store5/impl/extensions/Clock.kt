package org.mobilenativefoundation.store.store5.impl.extensions

import kotlin.time.Duration.Companion.hours

internal expect fun currentTimeMillis(): Long

internal fun now() = currentTimeMillis()

internal fun inHours(n: Int) = currentTimeMillis() + n.hours.inWholeMilliseconds
