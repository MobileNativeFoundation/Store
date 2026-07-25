@file:OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)

package org.mobilenativefoundation.store6.compose

import androidx.compose.runtime.State
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.testing.FakeStore
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CollectAsStateWithLifecycleTest {
    private class TestKey(val id: String) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace("users")

        override fun canonicalId(): String = id
    }

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun collectionPausesBelowMinActiveStateAndCatchesUpOnReentry(): TestResult {
        val store = FakeStore<TestKey, String>()
        val key = TestKey("1")
        store.setValue(key, "v1")
        val owner = TestOwner().apply { moveTo(Lifecycle.State.STARTED) }
        lateinit var state: State<StoreResult<String>>
        return runComposeTest(content = {
            state = store.collectAsStateWithLifecycle(key, lifecycleOwner = owner)
        }) { host ->
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "v1" }
            owner.moveTo(Lifecycle.State.CREATED)
            host.advanceFrame()
            store.setValue(key, "v2")
            host.advanceFrame()
            host.advanceFrame()
            assertEquals("v1", (state.value as StoreResult.Data<String>).value) // retained, not reset
            owner.moveTo(Lifecycle.State.STARTED)
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "v2" }
        }
    }

    /**
     * The documented contract is that re-entry retains the last result rather than resetting to
     * Loading. A `Store.stream` re-emits its current snapshot immediately, which would paper over
     * a reset, so this asserts against a replayless flow: on re-entry nothing arrives, and the
     * only way the state can be anything other than the retained value is an actual reset.
     */
    @Test
    fun reentryRetainsTheLastResultInsteadOfResettingToLoading(): TestResult {
        val flow = MutableSharedFlow<StoreResult<String>>() // replay = 0: re-entry re-delivers nothing
        val owner = TestOwner().apply { moveTo(Lifecycle.State.STARTED) }
        lateinit var state: State<StoreResult<String>>
        return runComposeTest(content = {
            state = flow.collectAsStoreStateWithLifecycle(lifecycleOwner = owner)
        }) { host ->
            flow.emit(TestStoreResults.data(value = "a", origin = Origin.FETCHER))
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "a" }
            owner.moveTo(Lifecycle.State.CREATED)
            host.advanceFrame()
            owner.moveTo(Lifecycle.State.STARTED)
            repeat(5) { host.advanceFrame() }
            assertTrue(
                state.value is StoreResult.Data<*>,
                "re-entry discarded the retained result: ${state.value}",
            )
            assertEquals("a", (state.value as StoreResult.Data<String>).value)
        }
    }

    @Test
    fun flowVariantPausesBelowMinActiveStateAndResumes(): TestResult {
        val flow = MutableSharedFlow<StoreResult<String>>(replay = 1)
        val owner = TestOwner().apply { moveTo(Lifecycle.State.STARTED) }
        val seeded = TestStoreResults.data(value = "seed", origin = Origin.MEMORY)
        lateinit var state: State<StoreResult<String>>
        return runComposeTest(content = {
            state = flow.collectAsStoreStateWithLifecycle(initial = seeded, lifecycleOwner = owner)
        }) { host ->
            assertSame(seeded, state.value)
            flow.emit(TestStoreResults.data(value = "a", origin = Origin.FETCHER))
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "a" }
            owner.moveTo(Lifecycle.State.CREATED)
            host.advanceFrame()
            flow.emit(TestStoreResults.data(value = "b", origin = Origin.FETCHER))
            host.advanceFrame()
            host.advanceFrame()
            assertEquals("a", (state.value as StoreResult.Data<String>).value) // paused, not collected
            owner.moveTo(Lifecycle.State.STARTED)
            host.awaitUntil { (state.value as? StoreResult.Data<String>)?.value == "b" }
        }
    }
}
