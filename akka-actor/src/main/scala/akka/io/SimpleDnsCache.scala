/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.util.concurrent.atomic.AtomicReference

import akka.annotation.InternalApi
import akka.io.Dns.Resolved
import akka.io.dns.CachePolicy._

import scala.annotation.tailrec
import scala.collection.immutable

private[io] trait PeriodicCacheCleanup {
  def cleanup(): Unit
}

class SimpleDnsCache extends Dns with PeriodicCacheCleanup {
  import SimpleDnsCache._

  private val cache = new AtomicReference(new Cache[String, Dns.Resolved](
    immutable.SortedSet()(expiryEntryOrdering[String]()),
    Map(), () ⇒ clock))

  private val nanoBase = System.nanoTime()

  override def cached(name: String): Option[Resolved] = {
    cache.get().get(name)
  }

  protected def clock(): Long = {
    val now = System.nanoTime()
    if (now - nanoBase < 0) 0
    else (now - nanoBase) / 1000000
  }

  @tailrec
  private[io] final def put(r: Resolved, ttl: CachePolicy): Unit = {
    val c = cache.get()
    if (!cache.compareAndSet(c, c.put(r.name, r, ttl)))
      put(r, ttl)
  }

  @tailrec
  override final def cleanup(): Unit = {
    val c = cache.get()
    if (!cache.compareAndSet(c, c.cleanup()))
      cleanup()
  }
}

object SimpleDnsCache {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[io] class Cache[K, V](queue: immutable.SortedSet[ExpiryEntry[K]], cache: immutable.Map[K, CacheEntry[V]], clock: () ⇒ Long) {
    def get(name: K): Option[V] = {
      for {
        e ← cache.get(name)
        if e.isValid(clock())
      } yield e.answer
    }

    def put(name: K, answer: V, ttl: CachePolicy): Cache[K, V] = {
      val until = ttl match {
        case Forever  ⇒ Long.MaxValue
        case Never    ⇒ clock() - 1
        case Ttl(ttl) ⇒ clock() + ttl.toMillis
      }

      new Cache[K, V](
        queue + new ExpiryEntry[K](name, until),
        cache + (name → CacheEntry(answer, until)),
        clock)
    }

    def cleanup(): Cache[K, V] = {
      val now = clock()
      var q = queue
      var c = cache
      while (q.nonEmpty && !q.head.isValid(now)) {
        val minEntry = q.head
        val name = minEntry.name
        q -= minEntry
        if (c.get(name).filterNot(_.isValid(now)).isDefined)
          c -= name
      }
      new Cache(q, c, clock)
    }
  }

  private case class CacheEntry[T](answer: T, until: Long) {
    def isValid(clock: Long): Boolean = clock < until
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[io] class ExpiryEntry[K](val name: K, val until: Long) extends Ordered[ExpiryEntry[K]] {
    def isValid(clock: Long): Boolean = clock < until
    override def compare(that: ExpiryEntry[K]): Int = -until.compareTo(that.until)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[io] def expiryEntryOrdering[K]() = new Ordering[ExpiryEntry[K]] {
    override def compare(x: ExpiryEntry[K], y: ExpiryEntry[K]): Int = {
      x.until.compareTo(y.until)
    }
  }
}
