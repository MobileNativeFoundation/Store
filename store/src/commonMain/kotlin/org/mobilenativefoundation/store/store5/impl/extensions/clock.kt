package org.mobilenativefoundation.store.store5.impl.extensions

import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

internal fun now() = Clock.System.now().toEpochMilliseconds()
internal fun inHours(n: Int) = Clock.System.now().plus(n.hours).toEpochMilliseconds()
internal fun inMinutes(n: Int) = Clock.System.now().plus(1.minutes).toEpochMilliseconds()