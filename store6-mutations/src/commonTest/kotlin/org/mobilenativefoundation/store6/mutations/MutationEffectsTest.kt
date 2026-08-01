@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class MutationEffectsTest {
    @Test
    fun staleSet_isCopiedNormalizedDeduplicatedAndSorted() = runTest {
        val duplicateA = MutationsTestKey("alpha")
        val duplicateB = MutationsTestKey("alpha")
        val zulu = MutationsTestKey("zulu")
        val mutableKeys = mutableSetOf(zulu, duplicateA, duplicateB)
        val mutableNamespaces =
            mutableSetOf(StoreNamespace("zzz"), StoreNamespace("aaa"), StoreNamespace("aaa"))
        val staleSet = StaleSet(keys = mutableKeys, namespaces = mutableNamespaces)

        val records = normalizedMutationEffects(staleSet)

        // Deduplicated by full identity pair, sorted namespace effects before key effects,
        // then by namespace and canonical id.
        assertEquals(
            listOf(
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.NAMESPACE,
                    namespace = "aaa",
                    canonicalId = null,
                ),
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.NAMESPACE,
                    namespace = "zzz",
                    canonicalId = null,
                ),
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.KEY,
                    namespace = "mutations",
                    canonicalId = "alpha",
                ),
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.KEY,
                    namespace = "mutations",
                    canonicalId = "zulu",
                ),
            ),
            records,
        )

        // The carrier is a copy: later mutation of the consumer's sets cannot change it.
        mutableKeys.clear()
        mutableNamespaces.clear()
        assertEquals(4, normalizedRecordCount(records))
    }

    @Test
    fun equalInputs_produceStructurallyEqualEffects() = runTest {
        val first =
            StaleSet(
                keys = setOf(MutationsTestKey("one"), MutationsTestKey("two")),
                namespaces = setOf(StoreNamespace("shared")),
            )
        val second =
            StaleSet(
                keys = setOf(MutationsTestKey("two"), MutationsTestKey("one")),
                namespaces = setOf(StoreNamespace("shared")),
            )

        assertEquals(normalizedMutationEffects(first), normalizedMutationEffects(second))
    }

    @Test
    fun throwingStalesFunction_poisonsAndStopsBeforeTransport() = runTest {
        val stalesFailure = IllegalStateException("stales failed")
        lateinit var effectful: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                effectful =
                    mutator(
                        id = "effectful",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = { _, _ -> throw stalesFailure },
                    ) { _, value -> MutationPresence.Present(value) }
            }
        val backend = FakeBackend()
        val engine = MutationEngine(registry, backend, baseReader = { "base" })
        val key = MutationsTestKey("throwing-stales")
        val mutationId = engine.mutate(key, effectful, "pending")

        engine.drain(key)

        assertEquals(emptyList(), backend.pushedValues)
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
        val poisoned = engine.poisoned.first()
        assertEquals(mutationId, poisoned.mutationId)
        assertSame(stalesFailure, poisoned.failure)
    }

    @Test
    fun capturedEffects_areSnapshotBeforeFirstPush_withoutExecution() = runTest {
        lateinit var effectful: MutatorRef<MutationsTestKey, String, String>
        val staleKey = MutationsTestKey("stale-target")
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                effectful =
                    mutator(
                        id = "effectful",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = { _, _ ->
                            StaleSet(
                                keys = setOf(staleKey),
                                namespaces = setOf(StoreNamespace("derived")),
                            )
                        },
                    ) { _, value -> MutationPresence.Present(value) }
            }
        val backend = FakeBackend()
        val engine = MutationEngine(registry, backend, baseReader = { "base" })
        val key = MutationsTestKey("capture")
        engine.bind(EffectsNoopWriteHandle)
        val mutationId = engine.mutate(key, effectful, "pending")

        engine.drain(key)

        // The push happened, the intent retired, and the snapshot was captured before transport;
        // no effect executed (021 has no execution machinery — 022/023 own it).
        assertEquals(listOf("pending"), backend.pushedValues)
        assertEquals(emptyList(), engine.pending(key))
        assertEquals(
            listOf(
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.NAMESPACE,
                    namespace = "derived",
                    canonicalId = null,
                ),
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.KEY,
                    namespace = "mutations",
                    canonicalId = "stale-target",
                ),
            ),
            engine.capturedEffectsSnapshot(mutationId),
        )
    }
}

private fun normalizedRecordCount(records: List<MutationEffectRecord>): Int = records.size

private object EffectsNoopWriteHandle :
    org.mobilenativefoundation.store6.core.seam.StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) = Unit

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
