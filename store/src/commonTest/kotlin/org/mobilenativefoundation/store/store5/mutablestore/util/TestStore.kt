package org.mobilenativefoundation.store.store5.mutablestore.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.mobilenativefoundation.store.cache5.Cache
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.RealStore

internal fun <Key : Any, Network : Any, Output : Any, Local : Any> testStore(
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
  scope: CoroutineScope = CoroutineScope(dispatcher),
  fetcher: Fetcher<Key, Network> = TestFetcher(),
  sourceOfTruth: SourceOfTruth<Key, Local, Output> = TestSourceOfTruth(),
  converter: Converter<Network, Local, Output> = TestConverter(),
  validator: Validator<Output> = TestValidator(),
  memoryCache: Cache<Key, Output> = TestCache(),
): RealStore<Key, Network, Output, Local> =
  RealStore(
    scope = scope,
    fetcher = fetcher,
    sourceOfTruth = sourceOfTruth,
    converter = converter,
    validator = validator,
    memCache = memoryCache,
  )
