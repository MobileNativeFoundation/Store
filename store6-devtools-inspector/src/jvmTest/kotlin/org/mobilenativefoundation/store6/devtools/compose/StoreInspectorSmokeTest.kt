@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools.compose

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import kotlin.test.Test

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
