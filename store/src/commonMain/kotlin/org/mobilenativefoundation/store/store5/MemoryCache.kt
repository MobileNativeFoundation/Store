package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.cache5.Cache

interface MemoryCache<Key : Any, Output : Any> : Cache<Key, Output>
