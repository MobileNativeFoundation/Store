@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal

import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints

internal expect val drainPlatformName: String

internal expect fun unsupportedConstraintKeys(constraints: DrainConstraints): List<String>
