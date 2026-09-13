package org.mobilenativefoundation.store6.mutations.sqldelight

import app.cash.sqldelight.driver.native.inMemoryDriver

internal actual fun freshJournalHarness(): JournalHarness =
    JournalHarness(inMemoryDriver(JournalTestSchema))
