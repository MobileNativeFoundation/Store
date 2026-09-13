package org.mobilenativefoundation.store6.testing

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.*
import kotlin.test.*

private class UserKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")
    override fun canonicalId(): String = id
}
private class User(val id: String, val name: String)

private sealed class UserUiState {
    data object Loading : UserUiState()
    class Ready(val name: String, val refreshing: Boolean) : UserUiState()
    class Failed(val error: StoreError) : UserUiState()
}

// docs:snippet:guides-testing-fake-store-view-model
private class UserViewModel(store: Store<UserKey, User>, key: UserKey, scope: CoroutineScope) {
    val state: StateFlow<UserUiState> =
        store.stream(key)
            .runningFold<StoreResult<User>, UserUiState>(UserUiState.Loading) { previous, result ->
                when (result) {
                    is StoreResult.Loading -> UserUiState.Loading
                    is StoreResult.Data -> UserUiState.Ready(result.value.name, result.refreshing)
                    is StoreResult.Revalidated -> previous // still fresh; keep what is shown
                    is StoreResult.Error -> UserUiState.Failed(result.error)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, UserUiState.Loading)
}

@OptIn(ExperimentalStoreApi::class)
class UserViewModelSampleTest {
    @Test
    fun viewModel_rendersLoadingDataAndErrorFromFakeStore() = runTest {
        val fake = FakeStore<UserKey, User>()
        val key = UserKey("42")
        fake.enqueueFetchValue(key, User("42", "Matt"))
        val vm = UserViewModel(fake, key, backgroundScope)
        vm.state.test {
            assertIs<UserUiState.Loading>(awaitItem())
            assertEquals("Matt", assertIs<UserUiState.Ready>(awaitItem()).name)

            fake.enqueueFetchError(key, TestStoreResults.fetchError("refresh users/42 failed: server 500. Retry later."), servedStale = true)
            fake.invalidate(key)
            assertTrue(assertIs<UserUiState.Ready>(awaitItem()).refreshing) // stale shown while refreshing
            assertIs<StoreError.Fetch>(assertIs<UserUiState.Failed>(awaitItem()).error)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("42", fake.interactions.filterIsInstance<FakeStoreInteraction.Stream>().single().key.canonicalId())
        fake.close()
    }
}
// docs:snippet:end
