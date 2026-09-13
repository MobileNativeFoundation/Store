package org.mobilenativefoundation.store6.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest as coroutineRunTest

private object UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) {}

    override fun insertTopDown(index: Int, instance: Unit) {}

    override fun move(from: Int, to: Int, count: Int) {}

    override fun remove(index: Int, count: Int) {}

    override fun onClear() {}
}

/**
 * Drives a composition entirely from the test scheduler: no wall clock, no `Dispatchers.Default`
 * hop, no nested `withTimeout`. Every frame is pumped explicitly through [advanceFrame].
 */
internal class ComposeHost(private val scope: TestScope, private val clock: BroadcastFrameClock) {
    fun advanceFrame() {
        Snapshot.sendApplyNotifications()
        scope.testScheduler.runCurrent()
        clock.sendFrame(0L)
        scope.testScheduler.runCurrent()
    }

    fun awaitUntil(limit: Int = 50, predicate: () -> Boolean) {
        repeat(limit) { if (predicate()) return else advanceFrame() }
        check(predicate()) { "condition not reached within $limit frames" }
    }
}

internal fun runComposeTest(
    content: @Composable () -> Unit,
    block: suspend TestScope.(ComposeHost) -> Unit,
): TestResult = runTest {
    val clock = BroadcastFrameClock()
    val recomposer = Recomposer(coroutineContext + clock)
    val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    val composition = Composition(UnitApplier, recomposer)
    val host = ComposeHost(this, clock)
    try {
        composition.setContent(content)
        host.advanceFrame()
        block(host)
    } finally {
        composition.dispose()
        recomposer.close()
        runner.cancelAndJoin()
    }
}

// One file-private 25s runTest shadow, no nested wall-clock waits.
private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
