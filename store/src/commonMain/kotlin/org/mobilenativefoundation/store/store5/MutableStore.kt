package org.mobilenativefoundation.store.store5

interface MutableStore<Key : Any, Common : Any> :
    Read.StreamWithConflictResolution<Key, Common>,
    Write<Key, Common>,
    Write.Stream<Key, Common>,
    Clear.Key<Key>,
    Clear
