package org.mobilenativefoundation.store6.benchmarks

import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Allocation evidence for FS-10's measured-plus-structural zero-cost-when-unset claim — the
 * "allocation-count measurement" StoreTelemetryTest.kt:114 defers to this module. Reports
 * CALLER-THREAD allocated bytes/op on the resident-serve path for telemetry-unset vs
 * NoopTelemetry-configured stores.
 *
 * REPORT-ONLY by design: prints a table, asserts nothing numeric (thresholds are OQ-6/first-data
 * territory), and skips gracefully off HotSpot. Known scope limit, stated wherever the numbers
 * are quoted: the fetch-duration mark allocates on the ENGINE thread (KeyEngine.launchFetch), so
 * a caller-thread probe cannot see it — the JMH none-vs-noop timing deltas and the optional local
 * `-prof gc` run cover the full cross-thread path.
 */
class TelemetryAllocationProbe {
    @OptIn(ExperimentalStoreApi::class)
    @Test
    fun residentServe_callerThreadAllocationDelta_reported() {
        val mx = ManagementFactory.getThreadMXBean()
        if (mx !is com.sun.management.ThreadMXBean || !mx.isThreadAllocatedMemorySupported) {
            println("TelemetryAllocationProbe: thread-allocation measurement unsupported on this JVM; skipping.")
            return
        }

        val allocatedMemoryWasEnabled = mx.isThreadAllocatedMemoryEnabled
        try {
            mx.isThreadAllocatedMemoryEnabled = true
            runAbbaProbe(mx)
        } finally {
            mx.isThreadAllocatedMemoryEnabled = allocatedMemoryWasEnabled
        }
    }

    @OptIn(ExperimentalStoreApi::class)
    private fun runAbbaProbe(mx: com.sun.management.ThreadMXBean) {
        val warmupOps = 20_000
        val measuredOps = 100_000
        val key = BenchKey("alloc-probe")
        val unsetSot = FakeSourceOfTruth<BenchKey, String>()
        val unsetStore = store {
            fetcher { error("unreachable: LocalOnly") }
            persistence(unsetSot)
        }
        val samples: AbbaSamples? = try {
            val noopSot = FakeSourceOfTruth<BenchKey, String>()
            val noopStore = store {
                fetcher { error("unreachable: LocalOnly") }
                persistence(noopSot)
                telemetry(NoopTelemetry)
            }
            try {
                runBlocking {
                    unsetSot.write(key, "seed")
                    noopSot.write(key, "seed")
                    repeat(warmupOps) { unsetStore.get(key, Freshness.LocalOnly) }
                    repeat(warmupOps) { noopStore.get(key, Freshness.LocalOnly) }

                    val tid = Thread.currentThread().id
                    val unsetFirst = measureSamplePerOp(
                        mx = mx,
                        tid = tid,
                        store = unsetStore,
                        key = key,
                        measuredOps = measuredOps,
                        label = "unset A",
                    ) ?: return@runBlocking null
                    val noopFirst = measureSamplePerOp(
                        mx = mx,
                        tid = tid,
                        store = noopStore,
                        key = key,
                        measuredOps = measuredOps,
                        label = "noop A",
                    ) ?: return@runBlocking null
                    val noopSecond = measureSamplePerOp(
                        mx = mx,
                        tid = tid,
                        store = noopStore,
                        key = key,
                        measuredOps = measuredOps,
                        label = "noop B",
                    ) ?: return@runBlocking null
                    val unsetSecond = measureSamplePerOp(
                        mx = mx,
                        tid = tid,
                        store = unsetStore,
                        key = key,
                        measuredOps = measuredOps,
                        label = "unset B",
                    ) ?: return@runBlocking null
                    AbbaSamples(
                        unsetFirst = unsetFirst,
                        unsetSecond = unsetSecond,
                        noopFirst = noopFirst,
                        noopSecond = noopSecond,
                    )
                }
            } finally {
                noopStore.close()
            }
        } finally {
            unsetStore.close()
        }

        if (samples == null) return
        val unsetMean = (samples.unsetFirst + samples.unsetSecond) / 2
        val noopMean = (samples.noopFirst + samples.noopSecond) / 2
        println("TelemetryAllocationProbe (resident LocalOnly get, caller-thread bytes/op; warmed ABBA):")
        println("  telemetry unset samples : ${samples.unsetFirst}, ${samples.unsetSecond} B/op")
        println("  NoopTelemetry samples   : ${samples.noopFirst}, ${samples.noopSecond} B/op")
        println("  telemetry unset mean    : $unsetMean B/op")
        println("  NoopTelemetry mean      : $noopMean B/op")
        println("  aggregate delta (noop-unset): ${noopMean - unsetMean} B/op")
    }

    private suspend fun measureSamplePerOp(
        mx: com.sun.management.ThreadMXBean,
        tid: Long,
        store: Store<BenchKey, String>,
        key: BenchKey,
        measuredOps: Int,
        label: String,
    ): Long? {
        if (Thread.currentThread().id != tid) {
            println(
                "TelemetryAllocationProbe: allocation measurement inconclusive/unsupported: " +
                    "caller thread changed before $label.",
            )
            return null
        }
        val before = mx.getThreadAllocatedBytes(tid)
        if (before < 0) {
            println(
                "TelemetryAllocationProbe: allocation measurement inconclusive/unsupported: " +
                    "$label returned before=$before.",
            )
            return null
        }
        repeat(measuredOps) { store.get(key, Freshness.LocalOnly) }
        if (Thread.currentThread().id != tid) {
            println(
                "TelemetryAllocationProbe: allocation measurement inconclusive/unsupported: " +
                    "caller thread changed during $label.",
            )
            return null
        }
        val after = mx.getThreadAllocatedBytes(tid)
        if (after < 0 || after < before) {
            println(
                "TelemetryAllocationProbe: allocation measurement inconclusive/unsupported: " +
                    "$label returned before=$before, after=$after.",
            )
            return null
        }
        return (after - before) / measuredOps
    }

    private data class AbbaSamples(
        val unsetFirst: Long,
        val unsetSecond: Long,
        val noopFirst: Long,
        val noopSecond: Long,
    )
}
