package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

@ExperimentalStoreApi
interface MutableStore<Key : Any, Output : Any> :
  Read.StreamWithConflictResolution<Key, Output>,
  Write<Key, Output>,
  Write.Stream<Key, Output>,
  Clear.Key<Key>,
  Clear
