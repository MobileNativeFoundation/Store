package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.AppContext

internal actual fun testAppContext(): AppContext = object : AppContext() {}
