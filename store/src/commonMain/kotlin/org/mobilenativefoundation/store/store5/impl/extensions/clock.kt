package org.mobilenativefoundation.store.store5.impl.extensions

import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Clock

internal fun now() = Clock.System.now().toEpochMilliseconds()

internal fun inHours(n: Int) = Clock.System.now().plus(n.hours).toEpochMilliseconds()
