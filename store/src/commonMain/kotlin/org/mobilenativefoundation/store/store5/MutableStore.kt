package org.mobilenativefoundation.store.store5

interface MutableStore<Key : Any, Output : Any> :
    Read.StreamWithConflictResolution<Key, Output>,
    Write<Key, Output>,
    Write.Stream<Key, Output>,
    Clear.Key<Key>,
    Clear
