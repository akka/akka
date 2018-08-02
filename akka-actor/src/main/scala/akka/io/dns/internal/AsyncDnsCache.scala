/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.util.concurrent.atomic.AtomicReference

import akka.annotation.InternalApi
import akka.io.{ Dns, PeriodicCacheCleanup }
import akka.io.dns.CachePolicy.CachePolicy

import scala.collection.immutable
import akka.io.SimpleDnsCache._
import akka.io.dns.internal.AsyncDnsResolver.{ Ipv4Type, Ipv6Type, QueryType }
import akka.io.dns.internal.DnsClient.Answer
import akka.io.dns.{ AAAARecord, ARecord }

import scala.annotation.tailrec
import scala.concurrent.duration._

/**
 * Internal API
 */
@InternalApi class AsyncDnsCache extends Dns with PeriodicCacheCleanup {
  private val cacheRef = new AtomicReference(new Cache[(String, QueryType), Answer](
    immutable.SortedSet()(expiryEntryOrdering()),
    immutable.Map(), () ⇒ clock))

  private val nanoBase = System.nanoTime()

  /**
   * Gets any IPv4 and IPv6 cached entries.
   * To get Srv or just one type use DnsProtocol
   */
  override def cached(name: String): Option[Dns.Resolved] = {
    for {
      ipv4 ← cacheRef.get().get((name, Ipv4Type))
      ipv6 ← cacheRef.get().get((name, Ipv6Type))
    } yield {
      Dns.Resolved(name, (ipv4.rrs ++ ipv6.rrs).collect {
        case r: ARecord    ⇒ r.ip
        case r: AAAARecord ⇒ r.ip
      })
    }
  }

  // Milliseconds since start
  protected def clock(): Long = {
    val now = System.nanoTime()
    if (now - nanoBase < 0) 0
    else (now - nanoBase) / 1000000
  }

  private[io] final def get(key: (String, QueryType)): Option[Answer] = {
    cacheRef.get().get(key)
  }

  @tailrec
  private[io] final def put(key: (String, QueryType), records: Answer, ttl: CachePolicy): Unit = {
    val cache: Cache[(String, QueryType), Answer] = cacheRef.get()
    if (!cacheRef.compareAndSet(cache, cache.put(key, records, ttl)))
      put(key, records, ttl)
  }

  @tailrec
  override final def cleanup(): Unit = {
    val c = cacheRef.get()
    if (!cacheRef.compareAndSet(c, c.cleanup()))
      cleanup()
  }
}
