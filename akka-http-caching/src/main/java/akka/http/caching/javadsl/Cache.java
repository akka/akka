/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.http.caching.javadsl;

import akka.annotation.ApiMayChange;
import akka.annotation.DoNotInherit;
import akka.japi.Creator;
import akka.japi.Procedure;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApiMayChange
@DoNotInherit
public interface Cache<K, V> {
  /**
   * Returns either the cached CompletionStage for the given key or evaluates the given value generating
   * function producing a `CompletionStage<V>`.
   */
  CompletionStage<V> getFuture(K key, Creator<CompletionStage<V>> genValue);

  /**
   * Returns either the cached {@code CompletionStage} for the key, or evaluates the given function which
   * should lead to eventual completion of the completable future.
   */
  CompletionStage<V> getOrFulfil(K key, Procedure<CompletableFuture<V>> f);

  /**
   * Returns either the cached CompletionStage for the given key or the given value as a CompletionStage
   */
  CompletionStage<V> getOrCreateStrict(K key, Creator<V> block);

  /**
   * Retrieves the CompletionStage instance that is currently in the cache for the given key.
   * Returns None if the key has no corresponding cache entry.
   */
  Optional<CompletionStage<V>> getOptional(K key);

  /**
   * Removes the cache item for the given key.
   */
  void remove(K key);

  /**
   * Clears the cache by removing all entries.
   */
  void clear();

  /**
   * Returns the set of keys in the cache, in no particular order
   * Should return in roughly constant time.
   * Note that this number might not reflect the exact keys of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  java.util.Set<K> getKeys();

  /**
   * Returns the upper bound for the number of currently cached entries.
   * Note that this number might not reflect the exact number of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  int size();
}
