package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.driver.native.inMemoryDriver

internal actual fun freshHarness(): SqlHarness = SqlHarness(inMemoryDriver(TestSchema))
