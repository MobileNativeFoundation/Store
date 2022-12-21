package org.mobilenativefoundation.store.store5.impl.extensions

import kotlinx.datetime.Clock

internal fun now() = Clock.System.now().toEpochMilliseconds()
