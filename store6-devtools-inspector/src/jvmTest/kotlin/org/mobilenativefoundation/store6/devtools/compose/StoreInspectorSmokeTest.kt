@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools.compose

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalTestApi::class)
class StoreInspectorSmokeTest {
    @Test
    fun inspectorCollectsMonitorUpdatesAfterComposition() = runComposeUiTest {
        val monitor = StoreDevtoolsMonitor()

        setContent { StoreInspector(monitor) }

        onAllNodesWithText("users / user-1").assertCountEquals(0)
        runOnIdle {
            monitor.onFetchStarted(TestKey("users", "user-1"))
            monitor.onServe(TestKey("users", "user-1"), Origin.MEMORY)
        }
        onNodeWithText("users / user-1").assertIsDisplayed()
    }

    @Test
    fun inspectorRendersBothDelimiterCollisionIdentities() = runComposeUiTest {
        val monitor = StoreDevtoolsMonitor()
        monitor.onServe(TestKey("a", "b/c"), Origin.MEMORY)
        monitor.onServe(TestKey("a/b", "c"), Origin.MEMORY)

        setContent { StoreInspector(monitor) }

        onNodeWithText("a / b/c").assertIsDisplayed()
        onNodeWithText("a/b / c").assertIsDisplayed()
    }

    @Test
    fun timelineRendersEachHistoricalDerivedStateAsAnExactLabel() = runComposeUiTest {
        val monitor = StoreDevtoolsMonitor()
        val key = TestKey("users", "user-1")
        monitor.onFetchSucceeded(key, 1.milliseconds)
        monitor.onInvalidated(key)
        monitor.onFetchStarted(key)
        monitor.onFetchFailed(key, TestStoreResults.fetchError("offline"), 1.milliseconds)
        monitor.onCleared(key)
        monitor.onFetchSucceeded(key, 1.milliseconds)

        setContent { StoreInspector(monitor) }

        onNodeWithText("users / user-1").performClick()
        onNodeWithText("STALE").assertIsDisplayed()
        onNodeWithText("FETCHING").assertIsDisplayed()
        onNodeWithText("ERROR").assertIsDisplayed()
        onNodeWithText("CLEARED").assertIsDisplayed()
    }

    @Test
    fun compactTimelineKeepsStateAndKindAboveTheKeyDetailLine() = runComposeUiTest {
        val monitor = StoreDevtoolsMonitor()
        monitor.onInvalidated(TestKey("u", "1"))

        setContent {
            StoreInspector(
                monitor = monitor,
                modifier = Modifier.width(220.dp).height(480.dp),
            )
        }

        onNodeWithText("u / 1").performClick()
        val state = onNodeWithText("STALE").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val kind = onNodeWithText("invalidate").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val keyDetail = onNodeWithText("u/1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue(kind.top == state.top, "state and exact v0 kind must share the first line")
        assertTrue(
            keyDetail.top >= maxOf(state.bottom, kind.bottom),
            "key/detail must remain visible on a second line at compact widths",
        )
    }

    @Test
    fun compactEventsKeepTheOriginalWeightedKindAndNeverRenderState() = runComposeUiTest {
        val monitor = StoreDevtoolsMonitor()
        monitor.onInvalidated(TestKey("u", "1"))

        setContent {
            StoreInspector(
                monitor = monitor,
                modifier = Modifier.width(220.dp).height(480.dp),
            )
        }

        onNodeWithText("Events").performClick()
        onAllNodesWithText("STALE").assertCountEquals(0)
        val kind = onNodeWithText("invalidate").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val keyDetail = onNodeWithText("u/1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue(
            kind.width > keyDetail.width,
            "Events must retain the original weighted-kind, fixed-detail row layout",
        )
    }

    @Test
    fun overlayFabExposesStateDependentSemanticsAndOpensThePanel() = runComposeUiTest {
        val monitor = StoreDevtoolsMonitor()

        setContent {
            StoreInspectorOverlay(monitor) {
                Text("Host content")
            }
        }

        onNodeWithContentDescription("Open Store6 inspector")
            .assertIsDisplayed()
            .performClick()
        onNodeWithText("Keys").assertIsDisplayed()
        onNodeWithContentDescription("Close Store6 inspector").assertIsDisplayed()
    }

    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }
}
