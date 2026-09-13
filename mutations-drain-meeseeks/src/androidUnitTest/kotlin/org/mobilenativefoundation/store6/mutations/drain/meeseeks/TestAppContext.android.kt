package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import android.app.Application
import dev.mattramotar.meeseeks.runtime.AppContext

internal actual fun testAppContext(): AppContext = Application()
