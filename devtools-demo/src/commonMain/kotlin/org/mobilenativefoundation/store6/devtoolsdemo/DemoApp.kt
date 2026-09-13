@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.devtoolsdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store6.compose.collectAsStateWithLifecycle
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import org.mobilenativefoundation.store6.devtools.StoreTelemetryLogger
import org.mobilenativefoundation.store6.devtools.storeTelemetryOf
import org.mobilenativefoundation.store6.devtools.compose.StoreInspectorOverlay
import org.mobilenativefoundation.store6.testing.FakeFetcher

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

data class User(val id: String, val name: String)

/** Live knobs the demo screen mutates while the store keeps fetching. */
class DemoControls {
    val latencyMillis = MutableStateFlow(1500L)
    val failFetches = MutableStateFlow(false)
}

/** Toggleable latency/failure around a testing [FakeFetcher]; refetches visibly change. */
class DemoFetcher(
    private val controls: DemoControls,
    val delegate: FakeFetcher<UserKey, User> = FakeFetcher(),
) : Fetcher<UserKey, User> {
    private var version = 0

    init {
        delegate.onUnscripted = { key, _ ->
            version += 1
            FetcherResult.Success(User(key.id, "User ${key.id} (v$version)"))
        }
    }

    override suspend fun fetch(key: UserKey, etag: String?): FetcherResult<User> {
        delay(controls.latencyMillis.value)
        if (controls.failFetches.value) {
            return FetcherResult.Error(IllegalStateException("Demo failure toggle is on"))
        }
        return delegate.fetch(key, etag)
    }
}

/** Process-wide demo graph: one store, one monitor, ONE builder line installing both sinks. */
object DemoGraph {
    val controls = DemoControls()
    val monitor = StoreDevtoolsMonitor()
    val users: Store<UserKey, User> = store {
        fetcher(DemoFetcher(controls))
        telemetry(storeTelemetryOf(StoreTelemetryLogger(), monitor))
    }
}

@Composable
fun DemoApp() {
    MaterialTheme {
        StoreInspectorOverlay(DemoGraph.monitor) {
            DemoScreen(DemoGraph.users, DemoGraph.controls)
        }
    }
}

@Composable
private fun DemoScreen(store: Store<UserKey, User>, controls: DemoControls) {
    val scope = rememberCoroutineScope()
    val key = remember { UserKey("1") }
    val result by store.collectAsStateWithLifecycle(key)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val current = result) {
            is StoreResult.Data<User> -> Card {
                Column(Modifier.padding(16.dp)) {
                    Text(current.value.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "origin=${current.origin} stale=${current.isStale} " +
                            "refreshing=${current.refreshing}",
                    )
                }
            }
            is StoreResult.Loading -> Text("Loading…")
            is StoreResult.Error -> Text(
                "Error (servedStale=${current.servedStale})",
                color = MaterialTheme.colorScheme.error,
            )
            is StoreResult.Revalidated -> Text("Revalidated (age ${current.age})")
        }

        val latency by controls.latencyMillis.collectAsState()
        Text("Fetch latency: ${latency}ms")
        Slider(
            value = latency.toFloat(),
            onValueChange = { controls.latencyMillis.value = it.toLong() },
            modifier = Modifier.semantics {
                contentDescription = "Fetch latency in milliseconds"
            },
            valueRange = 0f..5000f,
        )
        val failing by controls.failFetches.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fail fetches")
            Switch(
                checked = failing,
                onCheckedChange = { controls.failFetches.value = it },
                modifier = Modifier.semantics { contentDescription = "Fail fetches" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { store.invalidate(key) } }) { Text("Invalidate") }
            Button(onClick = { scope.launch { store.clear(key) } }) { Text("Clear") }
        }
    }
}
