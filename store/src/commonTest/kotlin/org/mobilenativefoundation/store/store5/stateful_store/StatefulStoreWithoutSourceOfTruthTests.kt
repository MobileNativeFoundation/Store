package org.mobilenativefoundation.store.store5.stateful_store

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)
class StatefulStoreWithoutSourceOfTruthTests {
    private val testScope = TestScope()
}
