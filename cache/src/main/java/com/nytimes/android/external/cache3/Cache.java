package com.nytimes.android.external.cache3;


import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface Cache<K, V> {

  /**
   * Returns the value associated with {@code key} in this cache, or {@code null} if there is no
   * cached value for {@code key}.
   *
   * @since 11.0
   */

  @Nullable
  V getIfPresent(Object key);

  /**
   * Returns the value associated with {@code key} in this cache, obtaining that value from
   * {@code valueLoader} if necessary. No observable state associated with this cache is modified
   * until loading completes. This method provides a simple substitute for the conventional
   * "if cached, return; otherwise create, cache and return" pattern.
   *
   * <p><b>Warning:</b> as with {@link CacheLoader#load}, {@code valueLoader} <b>must not</b> return
   * {@code null}; it may either return a non-null value or throw an exception.
   *
   * @throws ExecutionException if a checked exception was thrown while loading the value
   * @throws UncheckedExecutionException if an unchecked exception was thrown while loading the
   *     value
   * @throws ExecutionError if an error was thrown while loading the value
   *
   * @since 11.0
   */
  @Nonnull
  V get(K key, Callable<? extends V> valueLoader) throws ExecutionException;

  /**
   * Returns a map of the values associated with {@code keys} in this cache. The returned map will
   * only contain entries which are already present in the cache.
   *
   * @since 11.0
   */
  Map<K, V> getAllPresent(Iterable<?> keys);

  /**
   * Associates {@code value} with {@code key} in this cache. If the cache previously contained a
   * value associated with {@code key}, the old value is replaced by {@code value}.
   *
   * <p>Prefer {@link #get(Object, Callable)} when using the conventional "if cached, return;
   * otherwise create, cache and return" pattern.
   *
   * @since 11.0
   */
  void put(K key, V value);

  /**
   * Copies all of the mappings from the specified map to the cache. The effect of this call is
   * equivalent to that of calling {@code put(k, v)} on this map once for each mapping from key
   * {@code k} to value {@code v} in the specified map. The behavior of this operation is undefined
   * if the specified map is modified while the operation is in progress.
   *
   * @since 12.0
   */
  void putAll(Map<? extends K,? extends V> m);

  /**
   * Discards any cached value for key {@code key}.
   */
  void invalidate(Object key);

  /**
   * Discards any cached values for keys {@code keys}.
   *
   * @since 11.0
   */
  void invalidateAll(Iterable<?> keys);

  /**
   * Discards all entries in the cache.
   */
  void invalidateAll();

  /**
   * Returns the approximate number of entries in this cache.
   */
  long size();


  /**
   * Returns a view of the entries stored in this cache as a thread-safe map. Modifications made to
   * the map directly affect the cache.
   *
   * <p>Iterators from the returned map are at least <i>weakly consistent</i>: they are safe for
   * concurrent use, but if the cache is modified (including by eviction) after the iterator is
   * created, it is undefined which of the changes (if any) will be reflected in that iterator.
   */
  ConcurrentMap<K, V> asMap();

  /**
   * Performs any pending maintenance operations needed by the cache. Exactly which activities are
   * performed -- if any -- is implementation-dependent.
   */
  void cleanUp();
}
