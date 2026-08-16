package org.mobilenativefoundation.store6.benchmarks

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth

/**
 * Supplementary: quickstart-shape spin-up. The store side deliberately includes builder cost,
 * engine construction, and close() per invocation — that is the measurand (what a fresh
 * store-per-screen pattern would pay). The raw side is a fresh reader collection's first row.
 * Not part of the METRIC-1 headline ratio.
 */
@OptIn(ExperimentalStoreApi::class)
@State(Scope.Thread)
open class ColdStartBenchmark {
    private lateinit var sot: FakeSourceOfTruth<BenchKey, String>
    private val key = BenchKey("cold")

    @Setup
    fun setup() {
        sot = FakeSourceOfTruth()
        runBlocking { sot.write(key, "seed") }
    }

    @Benchmark
    fun storeColdConstructAndFirstData(bh: Blackhole) = runBlocking {
        val store = store<BenchKey, String> {
            fetcher { error("unreachable: all reads use Freshness.LocalOnly") }
            persistence(sot)
        }
        try {
            bh.consume(store.stream(key, Freshness.LocalOnly).first { it is StoreResult.Data })
        } finally {
            store.close()
        }
    }

    @Benchmark
    fun rawColdFirstRead(bh: Blackhole) = runBlocking {
        bh.consume(sot.reader(key).first())
    }
}
