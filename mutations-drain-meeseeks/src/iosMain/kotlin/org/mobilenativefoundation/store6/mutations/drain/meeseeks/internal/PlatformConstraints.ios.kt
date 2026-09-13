@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal

import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints

internal actual val drainPlatformName: String = "iOS"

internal actual fun unsupportedConstraintKeys(constraints: DrainConstraints): List<String> =
    emptyList()
