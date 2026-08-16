package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

internal actual fun freshHarness(): SqlHarness {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TestSchema.create(driver).value
    return SqlHarness(driver)
}
