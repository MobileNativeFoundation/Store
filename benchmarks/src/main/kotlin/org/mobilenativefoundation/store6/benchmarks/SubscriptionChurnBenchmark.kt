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
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth

/**
 * Supplementary: attach -> first Data -> cancel against a long-lived store, repeatedly. This is
 * the registry/reader-pipeline lifecycle that READER_PIPELINE_GRACE_MILLIS parks between
 * collections. The raw side is the same churn against the bare reader.
 */
@OptIn(ExperimentalStoreApi::class)
@State(Scope.Thread)
open class SubscriptionChurnBenchmark {
    private lateinit var sot: FakeSourceOfTruth<BenchKey, String>
    private lateinit var store: Store<BenchKey, String>
    private val key = BenchKey("churn")

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
    fun storeAttachFirstDataCancel(bh: Blackhole) = runBlocking {
        bh.consume(store.stream(key, Freshness.LocalOnly).first { it is StoreResult.Data })
    }

    @Benchmark
    fun rawAttachFirstRowCancel(bh: Blackhole) = runBlocking {
        bh.consume(sot.reader(key).first { it != null })
    }
}
