@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class MutationsWiringSpikeTest {
    /**
     * Successor to `lastOverlayRegistrationWins`.
     *
     * That spike proved core's last-overlay-registration-wins through core's public overlay
     * door. No such door exists on [MutationStoreBuilder], so the engine overlay installed by
     * the factory is always the sole and last registration — displacement is superseded by
     * compile-time absence. `MutationApiSurfaceTest` proves the ABI-level absence; this spike
     * proves the engine projection layer stays live through a fully-configured builder.
     */
    @Test
    fun engineOverlay_isSoleProjectionLayer_noBuilderDoorCanDisplaceIt() = runTest {
        lateinit var append: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                append =
                    mutator(
                        id = "append",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { base, suffix ->
                        MutationPresence.Present(
                            ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                        )
                    }
            }
        val backend = FakeBackend()
        val key = MutationsTestKey("sole-overlay")
        val users =
            mutationStore(
                registry = registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
                maxIdleKeys(8)
            }

        try {
            users.stream(key).test {
                assertEquals("base", awaitData().value)

                users.mutate(key, append, "+pending")
                var projected = awaitData()
                while (projected.value != "base+pending") {
                    projected = awaitData()
                }
                assertEquals(Origin.OVERLAY, projected.origin)
                assertFalse(projected.isStale)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
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
