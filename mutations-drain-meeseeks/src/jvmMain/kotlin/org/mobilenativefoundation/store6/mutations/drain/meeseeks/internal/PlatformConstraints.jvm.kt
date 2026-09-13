@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal

import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints

internal actual val drainPlatformName: String = "JVM"

internal actual fun unsupportedConstraintKeys(constraints: DrainConstraints): List<String> =
    buildList {
        if (constraints.requiresNetwork) add("requiresNetwork")
        if (constraints.requiresCharging) add("requiresCharging")
    }
