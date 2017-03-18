/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.http.caching.scaladsl

import java.util.Optional
import java.util.concurrent.{ CompletableFuture, CompletionStage }

import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.japi.{ Creator, Procedure }

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.compat.java8.FutureConverters.{ toJava ⇒ futureToJava, toScala ⇒ futureToScala }
import scala.concurrent.{ Future, Promise }

/**
 * API MAY CHANGE
 *
 * General interface implemented by all akka-http cache implementations.
 */
@ApiMayChange
@DoNotInherit
abstract class Cache[K, V] extends akka.http.caching.javadsl.Cache[K, V] {
  cache ⇒

  /**
   * Returns either the cached Future for the given key or evaluates the given value generating
   * function producing a `Future[V]`.
   */
  def apply(key: K, genValue: () ⇒ Future[V]): Future[V]

  /**
   * Returns either the cached Future for the key or evaluates the given function which
   * should lead to eventual completion of the promise.
   */
  def apply(key: K, f: Promise[V] ⇒ Unit): Future[V] =
    apply(key, () ⇒ { val p = Promise[V](); f(p); p.future })

  /**
   * Returns either the cached Future for the given key or the given value as a Future
   */
  def get(key: K, block: () ⇒ V): Future[V] =
    cache.apply(key, () ⇒ Future.successful(block()))

  /**
   * Retrieves the future instance that is currently in the cache for the given key.
   * Returns None if the key has no corresponding cache entry.
   */
  def get(key: K): Option[Future[V]]
  override def getOptional(key: K): Optional[CompletionStage[V]] =
    Optional.ofNullable(get(key).map(f ⇒ futureToJava(f)).orNull)

  /**
   * Removes the cache item for the given key.
   */
  override def remove(key: K): Unit

  /**
   * Clears the cache by removing all entries.
   */
  override def clear(): Unit

  /**
   * Returns the set of keys in the cache, in no particular order
   * Should return in roughly constant time.
   * Note that this number might not reflect the exact keys of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  def keys: immutable.Set[K]
  override def getKeys: java.util.Set[K] = keys.asJava

  final override def getFuture(key: K, genValue: Creator[CompletionStage[V]]): CompletionStage[V] =
    futureToJava(apply(key, () ⇒ futureToScala(genValue.create())))

  final override def getOrFulfil(key: K, f: Procedure[CompletableFuture[V]]): CompletionStage[V] =
    futureToJava(apply(key, promise ⇒ {
      val completableFuture = new CompletableFuture[V]
      f(completableFuture)
      promise.completeWith(futureToScala(completableFuture))
    }))

  /**
   * Returns either the cached CompletionStage for the given key or the given value as a CompletionStage
   */
  override def getOrCreateStrict(key: K, block: Creator[V]): CompletionStage[V] =
    futureToJava(get(key, () ⇒ block.create))

  /**
   * Returns the upper bound for the number of currently cached entries.
   * Note that this number might not reflect the exact number of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  def size(): Int
}
