package org.mobilenativefoundation.store.store5

interface MutableStore<Key : Any, CommonRepresentation : Any> :
    Read.StreamWithConflictResolution<Key, CommonRepresentation>,
    Write<Key, CommonRepresentation>,
    Write.Stream<Key, CommonRepresentation>,
    Clear.Key<Key>,
    Clear
