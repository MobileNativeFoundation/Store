package org.mobilenativefoundation.store.store5.impl.extensions

import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

internal fun now() = Clock.System.now().toEpochMilliseconds()

internal fun inHours(n: Int) = Clock.System.now().plus(n.hours).toEpochMilliseconds()
