/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.util.concurrent.atomic.AtomicReference

import akka.annotation.InternalApi
import akka.io.{ Dns, PeriodicCacheCleanup }

import scala.collection.immutable
import akka.io.SimpleDnsCache._
import akka.io.dns.internal.AsyncDnsResolver.{ Ipv4Type, Ipv6Type, QueryType }
import akka.io.dns.internal.DnsClient.Answer
import akka.io.dns.{ AAAARecord, ARecord }

import scala.annotation.tailrec

/**
 * Internal API
 */
@InternalApi class AsyncDnsCache extends Dns with PeriodicCacheCleanup {
  private val cache = new AtomicReference(new Cache[(String, QueryType), Answer](
    immutable.SortedSet()(expiryEntryOrdering()),
    immutable.Map(), clock))

  private val nanoBase = System.nanoTime()

  /**
   * Gets any IPv4 and IPv6 cached entries.
   * To get Srv or just one type use DnsProtocol
   */
  override def cached(name: String): Option[Dns.Resolved] = {
    for {
      ipv4 ← cache.get().get((name, Ipv4Type))
      ipv6 ← cache.get().get((name, Ipv6Type))
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
    cache.get().get(key)
  }

  @tailrec
  private[io] final def put(key: (String, QueryType), records: Answer, ttlMillis: Long): Unit = {
    val c = cache.get()
    if (!cache.compareAndSet(c, c.put(key, records, ttlMillis)))
      put(key, records, ttlMillis)
  }

  @tailrec
  override final def cleanup(): Unit = {
    val c = cache.get()
    if (!cache.compareAndSet(c, c.cleanup()))
      cleanup()
  }
}
