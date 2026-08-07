@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.FetchPlan
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.FreshnessContext
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The builder and factory forwarding contract.
 *
 * `MutationStoreBuilder` mirrors core's optional doors, exposes no overlay door, and retains the
 * exact selected-or-default Bookkeeper and SourceOfTruth for both the delegated core Store and
 * the mutation engine. ABI absence of an overlay setter, runtime, and write-handle exposure is
 * proved by the jvmTest dump test
 * (`MutationApiSurfaceTest.apiDumpContainsNoOverlaySetterRuntimeOrWriteHandleExposure`) plus
 * `:store6-mutations:apiCheck`; this common suite proves the doors' forwarding behavior.
 */
class MutationStoreBuilderTest {
    @Test
    fun factory_requiresRegistryServerResolverAndValueCodec() = runTest {
        // The registry, server, keyResolver, valueCodecVersion, and valueCodec are required
        // factory parameters, never builder doors. The named-argument call pins the exact
        // spelling; a functional read and mutate prove each required input reached the store.
        val mutators = testMutators()
        val store =
            mutationStore(
                registry = mutators.registry,
                server = BuilderRecordingMutationServer(),
                keyResolver = MutationKeyResolver { identity -> MutationsTestKey(identity.canonicalId) },
                valueCodecVersion = 1,
                valueCodec = StringMutationCodec,
                configure = {
                    fetcher { "confirmed" }
                },
            )
        try {
            val key = MutationsTestKey("factory-required")
            assertEquals("confirmed", store.get(key))
            val mutationId = store.mutate(key, mutators.append, "+mine")
            assertTrue(mutationId.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun explicitBookkeeper_isSameInstanceForStoreAndMutationEngine() = runTest {
        val mutators = testMutators()
        val bookkeeper = FakeBookkeeper()
        val store =
            buildStore(mutators) {
                fetcher { "confirmed" }
                bookkeeper(bookkeeper)
            }
        try {
            val key = MutationsTestKey("explicit-bookkeeper")
            assertEquals("confirmed", store.get(key))
            // Store side: the fetch commit recorded success into the caller's exact instance.
            val status = assertNotNull(bookkeeper.status(key))
            assertNotNull(status.meta)
            // Engine side: the mutation engine retained the caller's exact instance.
            assertSame(bookkeeper, store.bookkeeperRetainedByEngine)
        } finally {
            store.close()
        }
    }

    @Test
    fun defaultBookkeeper_isSameInstanceForStoreAndMutationEngine() = runTest {
        val mutators = testMutators()
        val store =
            buildStore(mutators) {
                fetcher { "confirmed" }
            }
        try {
            val key = MutationsTestKey("default-bookkeeper")
            // The unset door installs one mutations-owned default, never core's internal one.
            val retained = assertIs<MutationBookkeeper>(store.bookkeeperRetainedByEngine)
            assertEquals("confirmed", store.get(key))
            // The Store recorded its fetch success into the same instance the engine retained.
            val status = assertNotNull(retained.status(key))
            assertNotNull(status.meta)
        } finally {
            store.close()
        }
    }

    @Test
    fun explicitSourceOfTruth_isSameInstanceForStoreAndMutationEngine() = runTest {
        val mutators = testMutators()
        val sourceOfTruth = FakeSourceOfTruth<MutationsTestKey, String>()
        val store =
            buildStore(mutators) {
                fetcher { "confirmed" }
                persistence(sourceOfTruth)
            }
        try {
            val key = MutationsTestKey("explicit-sot")
            assertEquals("confirmed", store.get(key))
            // Store side: the engine persisted the fetched value through the caller's instance.
            assertEquals("confirmed", sourceOfTruth.reader(key).first())
            // Engine side: the mutation engine retained the caller's exact instance.
            assertSame(sourceOfTruth, store.sourceOfTruthRetainedByEngine)
        } finally {
            store.close()
        }
    }

    @Test
    fun defaultSourceOfTruth_isSameInstanceForStoreAndMutationEngine() = runTest {
        val mutators = testMutators()
        val store =
            buildStore(mutators) {
                fetcher { "confirmed" }
            }
        try {
            val key = MutationsTestKey("default-sot")
            val retained =
                assertIs<MutationSourceOfTruth<MutationsTestKey, String>>(
                    store.sourceOfTruthRetainedByEngine,
                )
            assertEquals("confirmed", store.get(key))
            // The Store persisted through the same instance the engine retained.
            assertEquals("confirmed", retained.reader(key).first())
        } finally {
            store.close()
        }
    }

    @Test
    fun builderForwardsEveryRuledConfigurationDoor() = runTest {
        val mutators = testMutators()
        val telemetry = RecordingTelemetry()
        val validator = RecordingFreshnessValidator()
        val wallClock = TestWallClock(startEpochMillis = 1_234L)
        val sourceOfTruth = FakeSourceOfTruth<MutationsTestKey, String>()
        val bookkeeper = FakeBookkeeper()
        var preconditionInvocations = 0
        var mergeInvocations = 0
        val store =
            buildStore(mutators) {
                fetcherOfResult { FetcherResult.Success("confirmed", etag = "etag-1") }
                persistence(sourceOfTruth)
                telemetry(telemetry)
                bookkeeper(bookkeeper)
                wallClock(wallClock)
                freshnessValidator(validator)
                maxIdleKeys(3)
                conflicts {
                    precondition { candidate ->
                        preconditionInvocations += 1
                        candidate.capturedMeta
                    }
                    merge { _, mine, _ ->
                        mergeInvocations += 1
                        MutationConflictResolution.Retry(mine)
                    }
                }
            }
        try {
            val key = MutationsTestKey("every-door")
            assertEquals("confirmed", store.get(key))
            store.mutate(key, mutators.append, "+pending")

            // fetcherOfResult door reached the engine and produced the fetched value above.
            // telemetry door: the configured observer saw the fetch lifecycle.
            assertEquals(1, telemetry.fetchStartedKeys.size)
            assertSame(key, telemetry.fetchStartedKeys.single())
            // freshnessValidator and wallClock doors: the configured planner ran against the
            // configured clock's now.
            assertTrue(validator.observedNowEpochMillis.isNotEmpty())
            assertTrue(validator.observedNowEpochMillis.all { now -> now == 1_234L })
            // bookkeeper and persistence doors: exact instances on the Store path...
            assertNotNull(bookkeeper.status(key))
            assertEquals("confirmed", sourceOfTruth.reader(key).first())
            // ...and the same exact instances retained for the engine.
            assertSame(bookkeeper, store.bookkeeperRetainedByEngine)
            assertSame(sourceOfTruth, store.sourceOfTruthRetainedByEngine)
            // conflicts door: nothing registered here runs for a merely pending mutation. The
            // selector runs when a drain prepares a generation, the merge only on a conflict.
            assertEquals(0, preconditionInvocations)
            assertEquals(0, mergeInvocations)
        } finally {
            store.close()
        }
    }

    @Test
    fun fetcherDoors_lastRegistrationWinsAcrossAllThreeForms() = runTest {
        val mutators = testMutators()
        val interfaceLast =
            buildStore(mutators) {
                fetcher { "from-lambda" }
                fetcherOfResult { FetcherResult.Success("from-result-lambda") }
                fetcher(
                    object : Fetcher<MutationsTestKey, String> {
                        override suspend fun fetch(
                            key: MutationsTestKey,
                            etag: String?,
                        ): FetcherResult<String> = FetcherResult.Success("from-interface")
                    },
                )
            }
        try {
            assertEquals("from-interface", interfaceLast.get(MutationsTestKey("fetcher-forms-1")))
        } finally {
            interfaceLast.close()
        }

        val lambdaLast =
            buildStore(mutators) {
                fetcherOfResult { FetcherResult.Success("from-result-lambda") }
                fetcher { "from-lambda" }
            }
        try {
            assertEquals("from-lambda", lambdaLast.get(MutationsTestKey("fetcher-forms-2")))
        } finally {
            lambdaLast.close()
        }
    }

    @Test
    fun maxIdleKeys_rejectsNegativeCountAtTheDoor() = runTest {
        val mutators = testMutators()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                buildStore(mutators) {
                    fetcher { "confirmed" }
                    maxIdleKeys(-1)
                }
            }
        assertEquals("maxIdleKeys must be >= 0, was -1.", failure.message)
    }

    @Test
    fun conflictsDoor_registrationValidatesSinglePreconditionAndSingleMerge() = runTest {
        val mutators = testMutators()
        assertFailsWith<IllegalArgumentException> {
            buildStore(mutators) {
                fetcher { "confirmed" }
                conflicts {
                    precondition { candidate -> candidate.capturedMeta }
                    precondition { null }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            buildStore(mutators) {
                fetcher { "confirmed" }
                conflicts {
                    merge { _, mine, _ -> MutationConflictResolution.Retry(mine) }
                    merge { _, _, _ -> MutationConflictResolution.ServerWins }
                }
            }
        }
    }

    @Test
    fun keyEvents_isExactDelegateRuntimeFlow() = runTest {
        // The facade re-exposes the delegate runtime's advisory flow unchanged. The
        // factory captures `runtime.keyEvents` exactly once; aliasing adds no `Rekeyed` variant
        // (KeyEvents constructors are core-internal, so this module cannot mint one), and the
        // flow retains the core lifecycle contract: it never completes, even after close.
        val mutators = testMutators()
        val store =
            buildStore(mutators) {
                fetcher { "confirmed" }
            }
        val key = MutationsTestKey("advisory-key-events")
        store.keyEvents.test {
            assertEquals("confirmed", store.get(key))
            val written = assertIs<KeyEvents.Written>(awaitItem())
            assertEquals(Origin.FETCHER, written.origin)
            assertEquals(key.identity(), written.key.identity())

            store.close()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingMutation_projectsThroughEngineOverlay_withoutPublicOverlayDoor() = runTest {
        // The builder has no overlay door (compile-time absence; the ABI proof is the jvmTest
        // dump test). The engine overlay installed by the factory is therefore the sole projection
        // layer, and pending mutations project with OVERLAY origin through the facade.
        val mutators = testMutators()
        val store =
            buildStore(mutators) {
                fetcher { "base" }
            }
        try {
            val key = MutationsTestKey("engine-overlay")
            store.stream(key).test {
                assertEquals("base", awaitData().value)

                store.mutate(key, mutators.append, "+pending")
                var projected = awaitData()
                while (projected.value != "base+pending") {
                    projected = awaitData()
                }
                assertEquals(Origin.OVERLAY, projected.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }
}

/** Fixed-version UTF-8 codec for the String values and args used by this suite. */
private object StringMutationCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

/** Minimal two-method server: acknowledges this client's value and confirms retirement. */
private class BuilderRecordingMutationServer : MutationServer<MutationsTestKey, String> {
    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> =
        when (val mine = request.mine) {
            is MutationPresence.Present ->
                MutationPresentAck(
                    authoritative = mine.value,
                    etag = null,
                    canonicalKey = null,
                )
            MutationPresence.Absent -> MutationAbsentAck(etag = null)
        }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

private class RecordingTelemetry : StoreTelemetry {
    val fetchStartedKeys = mutableListOf<StoreKey>()

    override fun onFetchStarted(key: StoreKey) {
        fetchStartedKeys += key
    }
}

private class RecordingFreshnessValidator : FreshnessValidator {
    val observedNowEpochMillis = mutableListOf<Long>()

    override fun plan(context: FreshnessContext): FetchPlan {
        observedNowEpochMillis += context.nowEpochMillis
        return if (context.hasResidentValue) {
            FetchPlan.Skip
        } else {
            FetchPlan.Fetch(servesResidentWhileFetching = false)
        }
    }
}

private class TestMutators(
    val registry: MutatorRegistry<MutationsTestKey, String>,
    val append: MutatorRef<MutationsTestKey, String, String>,
)

private fun testMutators(): TestMutators {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    val registry =
        mutatorRegistry<MutationsTestKey, String> {
            append =
                mutator(
                    id = "append",
                    version = 1,
                    codec = StringMutationCodec,
                    stales = { _, _ -> StaleSet(keys = emptySet(), namespaces = emptySet()) },
                    project = { base, args ->
                        when (base) {
                            is MutationPresence.Present ->
                                MutationPresence.Present(base.value + args)
                            MutationPresence.Absent -> null
                        }
                    },
                )
        }
    return TestMutators(registry = registry, append = append)
}

private fun buildStore(
    mutators: TestMutators,
    configure: MutationStoreBuilder<MutationsTestKey, String>.() -> Unit,
): MutationStore<MutationsTestKey, String> =
    mutationStore(
        registry = mutators.registry,
        server = BuilderRecordingMutationServer(),
        keyResolver = MutationKeyResolver { identity -> MutationsTestKey(identity.canonicalId) },
        valueCodecVersion = 1,
        valueCodec = StringMutationCodec,
        configure = configure,
    )

private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
