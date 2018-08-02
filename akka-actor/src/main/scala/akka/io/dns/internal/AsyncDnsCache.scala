/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.util.concurrent.atomic.AtomicReference

import akka.annotation.InternalApi
import akka.io.{ Dns, PeriodicCacheCleanup }
import akka.io.dns.CachePolicy.CachePolicy
import akka.io.SimpleDnsCache._
import akka.io.dns.DnsProtocol.{ Ip, RequestType, Resolved }
import akka.io.dns.{ AAAARecord, ARecord }

import scala.annotation.tailrec
import scala.collection.immutable

/**
 * Internal API
 */
@InternalApi class AsyncDnsCache extends Dns with PeriodicCacheCleanup {
  private val cacheRef = new AtomicReference(new Cache[(String, RequestType), Resolved](
    immutable.SortedSet()(expiryEntryOrdering()),
    immutable.Map(), () ⇒ clock))

  private val nanoBase = System.nanoTime()

  /**
   * Gets any IPv4 and IPv6 cached entries.
   * To get Srv or just one type use DnsProtocol
   */
  override def cached(name: String): Option[Dns.Resolved] = {
    val ipv4 = cacheRef.get().get((name, Ip(ipv6 = false))).toList.flatMap(_.records)
    val ipv6 = cacheRef.get().get((name, Ip(ipv4 = false))).toList.flatMap(_.records)
    val both = cacheRef.get().get((name, Ip())).toList.flatMap(_.records)

    val all = (ipv4 ++ ipv6 ++ both).collect {
      case r: ARecord    ⇒ r.ip
      case r: AAAARecord ⇒ r.ip
    }
    if (all.isEmpty) None
    else Some(Dns.Resolved(name, all))
  }

  // Milliseconds since start
  protected def clock(): Long = {
    val now = System.nanoTime()
    if (now - nanoBase < 0) 0
    else (now - nanoBase) / 1000000
  }

  private[io] final def get(key: (String, RequestType)): Option[Resolved] = {
    cacheRef.get().get(key)
  }

  @tailrec
  private[io] final def put(key: (String, RequestType), records: Resolved, ttl: CachePolicy): Unit = {
    val c = cacheRef.get()
    if (!cacheRef.compareAndSet(c, c.put(key, records, ttl)))
      put(key, records, ttl)
  }

  @tailrec
  override final def cleanup(): Unit = {
    val c = cacheRef.get()
    if (!cacheRef.compareAndSet(c, c.cleanup()))
      cleanup()
  }
}
