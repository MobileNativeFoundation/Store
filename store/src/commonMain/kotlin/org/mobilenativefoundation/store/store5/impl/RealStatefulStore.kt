@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.Clear
import org.mobilenativefoundation.store.store5.Read
import org.mobilenativefoundation.store.store5.StatefulStore
import org.mobilenativefoundation.store.store5.StatefulStoreKey

internal class RealStatefulStore<Key : StatefulStoreKey, Network : Any, Output : Any, Local : Any>(
        private val delegate: RealStore<Key, Network, Output, Local>,
) : StatefulStore<Key, Output>, Read.Stream<Key, Output> by delegate, Clear.Key<Key> by delegate, Clear.All by delegate