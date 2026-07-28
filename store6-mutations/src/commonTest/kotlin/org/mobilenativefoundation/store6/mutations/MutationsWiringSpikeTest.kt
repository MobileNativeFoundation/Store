@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class MutationsWiringSpikeTest {
    @Test
    fun lastOverlayRegistrationWins() = runTest {
        val key = MutationsTestKey("last-overlay")
        val firstSignals = MutableSharedFlow<StoreKey>(replay = 1)
        val secondSignals = MutableSharedFlow<StoreKey>(replay = 1)
        val secondPending = MutableStateFlow<String?>(null)
        val firstOverlayInvoked = MutableStateFlow(false)
        val store = store<MutationsTestKey, String> {
            fetcher { "base" }
            overlay(
                object : Overlay<MutationsTestKey, String> {
                    override fun apply(
                        key: MutationsTestKey,
                        base: String?,
                    ): String? {
                        firstOverlayInvoked.value = true
                        return base
                    }

                    override val changes: Flow<StoreKey> = firstSignals
                },
            )
            overlay(
                object : Overlay<MutationsTestKey, String> {
                    override fun apply(
                        key: MutationsTestKey,
                        base: String?,
                    ): String? = base?.plus(secondPending.value.orEmpty())

                    override val changes: Flow<StoreKey> = secondSignals
                },
            )
        }

        try {
            store.stream(key).test {
                assertEquals("base", awaitData().value)
                assertFalse(firstOverlayInvoked.value)

                secondPending.value = "+second"
                secondSignals.emit(key)
                var projected = awaitData()
                while (projected.value != "base+second") {
                    projected = awaitData()
                }
                assertEquals(Origin.OVERLAY, projected.origin)
                assertFalse(firstOverlayInvoked.value)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }
}

private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
