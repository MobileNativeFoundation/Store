package org.mobilenativefoundation.store.store5

interface StatefulStore<Key : StatefulStoreKey, Output : Any> :
        Read.Stream<Key, Output>,
        Clear.Key<Key>,
        Clear
