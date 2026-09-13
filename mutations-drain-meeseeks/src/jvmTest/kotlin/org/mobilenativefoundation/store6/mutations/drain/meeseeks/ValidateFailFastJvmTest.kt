@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints

class ValidateFailFastJvmTest {
    @Test
    fun validateFailsFastOnJvmDefaults() = runTest {
        val scheduler = MeeseeksDrainScheduler { error("manager must not be consulted") }

        val failure =
            assertFailsWith<IllegalArgumentException> {
                scheduler.validate(DrainConstraints())
            }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("JVM"))
        assertTrue(message.contains("requiresNetwork"))
        assertTrue(
            message.contains(
                "DrainConstraints(requiresNetwork = false, requiresCharging = false)",
            ),
        )
        assertTrue(message.contains("InProcessDrainScheduler"))
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
