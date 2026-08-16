package org.mobilenativefoundation.store6.benchmarks

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth

/**
 * Supplementary: the one-shot resident read path. Between invocations the key quiesces, so ops
 * exercise the resident/idle-revive path (maxIdleKeys default 128 keeps the engine parked, never
 * destroyed) — stated in the first-data doc alongside the number.
 */
@OptIn(ExperimentalStoreApi::class)
@State(Scope.Thread)
open class GetPathBenchmark {
    private lateinit var sot: FakeSourceOfTruth<BenchKey, String>
    private lateinit var store: Store<BenchKey, String>
    private val key = BenchKey("get")

    @Setup
    fun setup() {
        sot = FakeSourceOfTruth()
        store = store {
            fetcher { error("unreachable: all reads use Freshness.LocalOnly") }
            persistence(sot)
        }
        runBlocking {
            sot.write(key, "seed")
            store.get(key, Freshness.LocalOnly)
        }
    }

    @TearDown
    fun tearDown() {
        store.close()
    }

    @Benchmark
    fun storeGetResident(bh: Blackhole) = runBlocking {
        bh.consume(store.get(key, Freshness.LocalOnly))
    }

    @Benchmark
    fun rawReaderFirst(bh: Blackhole) = runBlocking {
        bh.consume(sot.reader(key).first())
    }
}
