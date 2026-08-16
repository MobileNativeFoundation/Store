package org.mobilenativefoundation.store6.benchmarks

import kotlinx.benchmark.Blackhole
import kotlin.test.Test

/**
 * Executes every benchmark method once with tiny parameters, without the JMH runner. This test is
 * what makes the blocking `:store6-benchmarks:build` CI step a rot guard: benchmark code cannot
 * silently decay while the measurement lane stays non-blocking. Numbers are not read here.
 */
class HarnessSmokeTest {
    // JMH's sanctioned escape hatch for constructing a Blackhole outside the runner; the string is
    // JMH API (org.openjdk.jmh.infra.Blackhole's guarded constructor).
    private val bh = Blackhole(
        "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.",
    )

    @Test
    fun streamEmissionBenchmark_bothSides_runOnce() {
        val b = StreamEmissionBenchmark()
        b.writes = 8
        b.collectors = 2
        b.paced = true
        b.setup()
        try {
            b.rawSotFlow(bh)
            b.storeStream(bh)
        } finally {
            b.tearDown()
        }
    }

    @Test
    fun streamEmissionBenchmark_burstRegime_runsOnce() {
        val b = StreamEmissionBenchmark()
        b.writes = 8
        b.collectors = 1
        b.paced = false
        b.setup()
        try {
            b.rawSotFlow(bh)
            b.storeStream(bh)
        } finally {
            b.tearDown()
        }
    }

    @Test
    fun coldStartBenchmark_bothSides_runOnce() {
        val b = ColdStartBenchmark()
        b.setup()
        b.storeColdConstructAndFirstData(bh)
        b.rawColdFirstRead(bh)
    }

    @Test
    fun getPathBenchmark_bothSides_runOnce() {
        val b = GetPathBenchmark()
        b.setup()
        try {
            b.storeGetResident(bh)
            b.rawReaderFirst(bh)
        } finally {
            b.tearDown()
        }
    }

    @Test
    fun subscriptionChurnBenchmark_bothSides_runOnce() {
        val b = SubscriptionChurnBenchmark()
        b.setup()
        try {
            b.storeAttachFirstDataCancel(bh)
            b.rawAttachFirstRowCancel(bh)
        } finally {
            b.tearDown()
        }
    }

    @Test
    fun telemetryOverheadBenchmark_bothVariants_runOnce() {
        for (variant in listOf("none", "noop")) {
            val b = TelemetryOverheadBenchmark()
            b.telemetry = variant
            b.setup()
            try {
                b.fetchGet(bh)
                b.residentServe(bh)
                b.streamEmissions(bh)
            } finally {
                b.tearDown()
            }
        }
    }
}
