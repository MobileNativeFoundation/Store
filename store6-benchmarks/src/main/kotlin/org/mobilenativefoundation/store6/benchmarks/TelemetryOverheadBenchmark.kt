package org.mobilenativefoundation.store6.benchmarks

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth

/**
 * The measured half of FS-10's "zero cost when unset" (008's deferral; StoreTelemetryTest.kt:114).
 *
 * FS-10's evidence is measured plus structural, not a literal differential against a telemetry-free
 * engine. Structural inspection and tests establish that telemetry=none leaves the install point
 * null, each call site takes its null fast path, and KeyEngine.launchFetch allocates no
 * fetch-duration mark. telemetry=noop installs NoopTelemetry, so this benchmark estimates the
 * incremental configured-noop overhead relative to that unset/null fast path: non-null branches,
 * the fetch-duration mark, and virtual calls into no-op bodies. There is no seam-less engine to
 * compare, so the delta neither proves literal zero cost nor bounds total telemetry machinery cost
 * relative to such an engine.
 *
 * fetchGet: full fetch cycle per op (onFetchStarted + mark + onFetchSucceeded + onServe), via
 * MustBeFresh against a constant fetcher on the DSL-default in-memory SoT (public builder path).
 * residentServe: resident LocalOnly get (onServe only). streamEmissions: each timed invocation
 * launches one collector, waits for its first public result and an epoch-unique readiness marker,
 * then runs a 100-write cooperatively yielded schedule through the attached stream. Its score
 * includes that precondition, the schedule, and final observation. Both variants may conflate
 * intermediate writes, and onServe runs once per public delivery. The none/noop pair uses the same
 * schedule.
 */
@OptIn(ExperimentalStoreApi::class)
@State(Scope.Thread)
open class TelemetryOverheadBenchmark {
    @Param("none", "noop")
    var telemetry: String = "none"

    private lateinit var sot: FakeSourceOfTruth<BenchKey, String>
    private lateinit var fetchStore: Store<BenchKey, String>
    private lateinit var localStore: Store<BenchKey, String>
    private val key = BenchKey("telemetry")
    private var epoch = 0L

    @Setup
    fun setup() {
        sot = FakeSourceOfTruth()
        fetchStore = store {
            fetcher { "fetched" }
            if (telemetry == "noop") telemetry(NoopTelemetry)
        }
        localStore = store {
            fetcher { error("unreachable: all localStore reads use Freshness.LocalOnly") }
            persistence(sot)
            if (telemetry == "noop") telemetry(NoopTelemetry)
        }
        runBlocking {
            sot.write(key, "seed")
            localStore.get(key, Freshness.LocalOnly)
        }
    }

    @TearDown
    fun tearDown() {
        fetchStore.close()
        localStore.close()
    }

    @Benchmark
    fun fetchGet(bh: Blackhole) = runBlocking {
        bh.consume(fetchStore.get(key, Freshness.MustBeFresh))
    }

    @Benchmark
    fun residentServe(bh: Blackhole) = runBlocking {
        bh.consume(localStore.get(key, Freshness.LocalOnly))
    }

    @Benchmark
    fun streamEmissions(bh: Blackhole) = runBlocking {
        epoch += 1
        val readiness = "ready-$epoch"
        val sentinel = "v-$epoch-100"
        coroutineScope {
            val initialReady = CompletableDeferred<Unit>()
            val attachedReady = CompletableDeferred<Unit>()
            launch {
                var first = true
                bh.consume(
                    localStore.stream(key, Freshness.LocalOnly).first {
                        if (first) {
                            first = false
                            initialReady.complete(Unit)
                        }
                        if (it is StoreResult.Data && it.value == readiness) {
                            attachedReady.complete(Unit)
                        }
                        it is StoreResult.Data && it.value == sentinel
                    },
                )
            }
            initialReady.await()
            sot.write(key, readiness)
            attachedReady.await()
            for (i in 1..100) {
                sot.write(key, "v-$epoch-$i")
                yield()
            }
        }
    }
}
