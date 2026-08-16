package org.mobilenativefoundation.store6.mutations.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

internal actual fun freshJournalHarness(): JournalHarness =
    JournalHarness(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
