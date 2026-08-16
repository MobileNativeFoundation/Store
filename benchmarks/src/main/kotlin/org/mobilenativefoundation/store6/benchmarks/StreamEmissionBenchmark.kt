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
 * METRIC-1: stream-emission overhead versus the raw SoT flow.
 *
 * Both sides observe the SAME FakeSourceOfTruth class under the SAME write schedule, awaiting the
 * SAME epoch-unique sentinel. Within each timed invocation, before workload writes, every
 * collector first receives a public emission and then observes an epoch-unique attachment marker.
 * That marker is one additional common write/observation per invocation outside the W=1000
 * workload but inside the timed operation. It is an attachment precondition, not workload data,
 * and proves the Store's long-lived reader/fan-out pipeline is attached before W begins.
 *
 * With collectors=1, reader multiplicity matches and storeStream/rawSotFlow is the METRIC-1
 * engine-overhead headline: registry, reader pipeline, planning, conflation, projection, telemetry
 * null-guard, and dispatch hops. The engine runs on Dispatchers.Default while the raw side is
 * cooperative on the runBlocking thread; that asymmetry is Store cost and stays in the ratio.
 *
 * collectors=8 is a separately interpreted fan-out/topology cell: raw opens eight FakeSourceOfTruth
 * reader chains while Store shares one upstream and fans out. It is useful end-to-end scaling data,
 * not an isolated engine-overhead ratio.
 *
 * The reported score includes collector launch, attachment, readiness, the W schedule, and final
 * observation. It is an end-to-end attach-plus-schedule measurand, not pure W-only latency or
 * per-emission unit cost. Both sides may conflate arbitrary intermediate writes. paced=true is a
 * cooperatively yielded writer schedule, not an acknowledgement or per-emission guarantee;
 * paced=false is the burst/conflation schedule. The two bracket real workloads.
 */
@OptIn(ExperimentalStoreApi::class)
@State(Scope.Thread)
open class StreamEmissionBenchmark {
    @Param("1000")
    var writes: Int = 0

    @Param("1", "8")
    var collectors: Int = 0

    @Param("false", "true")
    var paced: Boolean = false

    private lateinit var sot: FakeSourceOfTruth<BenchKey, String>
    private lateinit var store: Store<BenchKey, String>
    private val key = BenchKey("stream")
    private var epoch = 0L

    @Setup
    fun setup() {
        sot = FakeSourceOfTruth()
        store = store {
            fetcher { error("unreachable: all reads use Freshness.LocalOnly") }
            persistence(sot)
        }
        runBlocking { sot.write(key, "seed") }
    }

    @TearDown
    fun tearDown() {
        store.close()
    }

    @Benchmark
    fun rawSotFlow(bh: Blackhole) = runBlocking {
        epoch += 1
        val readiness = "ready-$epoch"
        val sentinel = "v-$epoch-$writes"
        coroutineScope {
            val initialReadies = List(collectors) { CompletableDeferred<Unit>() }
            val attachedReadies = List(collectors) { CompletableDeferred<Unit>() }
            repeat(collectors) { c ->
                launch {
                    var first = true
                    bh.consume(
                        sot.reader(key).first {
                            if (first) {
                                first = false
                                initialReadies[c].complete(Unit)
                            }
                            if (it == readiness) attachedReadies[c].complete(Unit)
                            it == sentinel
                        },
                    )
                }
            }
            initialReadies.forEach { it.await() }
            sot.write(key, readiness)
            attachedReadies.forEach { it.await() }
            runSchedule()
        }
    }

    @Benchmark
    fun storeStream(bh: Blackhole) = runBlocking {
        epoch += 1
        val readiness = "ready-$epoch"
        val sentinel = "v-$epoch-$writes"
        coroutineScope {
            val initialReadies = List(collectors) { CompletableDeferred<Unit>() }
            val attachedReadies = List(collectors) { CompletableDeferred<Unit>() }
            repeat(collectors) { c ->
                launch {
                    var first = true
                    bh.consume(
                        store.stream(key, Freshness.LocalOnly).first {
                            if (first) {
                                first = false
                                initialReadies[c].complete(Unit)
                            }
                            if (it is StoreResult.Data && it.value == readiness) {
                                attachedReadies[c].complete(Unit)
                            }
                            it is StoreResult.Data && it.value == sentinel
                        },
                    )
                }
            }
            initialReadies.forEach { it.await() }
            sot.write(key, readiness)
            attachedReadies.forEach { it.await() }
            runSchedule()
        }
    }

    /** The identical write schedule both benchmark methods run after all collectors attach. */
    private suspend fun runSchedule() {
        for (i in 1..writes) {
            sot.write(key, "v-$epoch-$i")
            if (paced) yield()
        }
    }
}
