package org.mobilenativefoundation.store6.extensionprobe

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private class ProbeKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("probe")

    override fun canonicalId(): String = id
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class ExtensionProbeTest {
    @Test
    fun factories_constructEveryResultState_withoutInternals() {
        val data =
            StoreResults.data(
                value = "v",
                origin = Origin.OVERLAY,
                age = Duration.ZERO,
                isStale = true,
                refreshing = true,
            )
        assertEquals("v", data.value)
        assertEquals(Origin.OVERLAY, data.origin)
        StoreResults.loading()
        assertEquals(Duration.ZERO, StoreResults.revalidated(Duration.ZERO).age)
        val error =
            StoreResults.error(
                StoreResults.missing(ProbeKey("1"), "absent"),
                servedStale = true,
            )
        assertTrue(error.servedStale)
        assertEquals("m", StoreResults.fetchError("m").message)
        assertEquals("m", StoreResults.persistenceError("m").message)
        assertEquals("m", StoreResults.conversionError("m").message)
        assertEquals("m", StoreResults.freshnessUnsatisfiable("m").message)
        val conflict = StoreResults.conflict(serverMeta = null, message = "m")
        assertEquals("m", conflict.message)
        assertNull(conflict.serverMeta)
        val missing = StoreResults.missing(ProbeKey("k"), "m")
        assertEquals("k", missing.key.canonicalId())
        assertEquals("m", missing.message)
        assertEquals("m", StoreResults.exception(StoreResults.fetchError("m")).message)
    }

    @Test
    fun metricsDecorator_composesSeamOnly() = runTest {
        val metrics = MetricsTelemetry()
        val store = store<ProbeKey, String> {
            fetcher { "v" }
            telemetry(metrics)
        }
        val logs = mutableListOf<String>()
        val logging = LoggingStore(store, logs::add)

        assertEquals("v", logging.get(ProbeKey("1")))

        // Fetch telemetry runs before the ticket completes, so get() returning is the causal
        // barrier for these exact counters.
        assertEquals(1, metrics.fetchStarts)
        assertEquals(1, metrics.fetchSuccesses)
        assertEquals(1, metrics.serves)
        assertNull(logging.runtime())
        assertNotNull(store.runtime())
        store.close()
    }

    @Test
    fun mutationDrainVocabulary_isTypedAndExtensionOwned() {
        val recorded = mutableListOf<MutationDrainEvent>()
        val sink = MutationEventSink(recorded::add)
        val failure =
            MutationDrainEvent.Failed(
                mutationId = "m-1",
                error = StoreResults.fetchError("offline"),
            )

        sink.onEvent(failure)

        assertEquals(listOf<MutationDrainEvent>(failure), recorded)
        assertEquals("offline", assertIs<StoreError.Fetch>(failure.error).message)
    }
}
