package akka.io

import java.util.concurrent.atomic.AtomicReference
import akka.io.Dns.Resolved

import scala.annotation.tailrec
import scala.collection.immutable

private[io] sealed trait PeriodicCacheCleanup {
  def cleanup(): Unit
}

class SimpleDnsCache extends Dns with PeriodicCacheCleanup {
  import akka.io.SimpleDnsCache._

  private val cache = new AtomicReference(new Cache(
    immutable.SortedSet()(ExpiryEntryOrdering),
    immutable.Map(), clock))

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
  private[io] final def put(r: Resolved, ttlMillis: Long): Unit = {
    val c = cache.get()
    if (!cache.compareAndSet(c, c.put(r, ttlMillis)))
      put(r, ttlMillis)
  }

  @tailrec
  override final def cleanup(): Unit = {
    val c = cache.get()
    if (!cache.compareAndSet(c, c.cleanup()))
      cleanup()
  }
}

object SimpleDnsCache {
  private class Cache(queue: immutable.SortedSet[ExpiryEntry], cache: immutable.Map[String, CacheEntry], clock: () ⇒ Long) {
    def get(name: String): Option[Resolved] = {
      for {
        e ← cache.get(name)
        if e.isValid(clock())
      } yield e.answer
    }

    def put(answer: Resolved, ttlMillis: Long): Cache = {
      val until = clock() + ttlMillis

      new Cache(
        queue + new ExpiryEntry(answer.name, until),
        cache + (answer.name → CacheEntry(answer, until)),
        clock)
    }

    def cleanup(): Cache = {
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

  private case class CacheEntry(answer: Dns.Resolved, until: Long) {
    def isValid(clock: Long): Boolean = clock < until
  }

  private class ExpiryEntry(val name: String, val until: Long) extends Ordered[ExpiryEntry] {
    def isValid(clock: Long): Boolean = clock < until
    override def compare(that: ExpiryEntry): Int = -until.compareTo(that.until)
  }

  private object ExpiryEntryOrdering extends Ordering[ExpiryEntry] {
    override def compare(x: ExpiryEntry, y: ExpiryEntry): Int = {
      x.until.compareTo(y.until)
    }
  }
}
