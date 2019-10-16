/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

import akka.io.dns.ARecord
import akka.io.dns.CachePolicy.Ttl
import akka.io.dns.DnsProtocol
import akka.io.dns.DnsProtocol.Ip
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._
import scala.collection.immutable

class SimpleDnsCacheSpec extends WordSpec with Matchers {
  "Cache" should {
    "not reply with expired but not yet swept out entries" in {
      val localClock = new AtomicLong(0)
      val cache: SimpleDnsCache = new SimpleDnsCache() {
        override protected def clock() = localClock.get
      }
      val ttl = Ttl.fromPositive(5000.millis)
      val cacheEntry = DnsProtocol.Resolved(
        "test.local",
        immutable.Seq(ARecord("test.local", ttl, InetAddress.getByName("127.0.0.1"))))
      cache.put(("test.local", Ip()), cacheEntry, ttl)

      cache.cached(DnsProtocol.Resolve("test.local")) should ===(Some(cacheEntry))
      localClock.set(4999)
      cache.cached(DnsProtocol.Resolve("test.local")) should ===(Some(cacheEntry))
      localClock.set(5000)
      cache.cached(DnsProtocol.Resolve("test.local")) should ===(None)
    }

    "sweep out expired entries on cleanup()" in {
      val localClock = new AtomicLong(0)
      val cache: SimpleDnsCache = new SimpleDnsCache() {
        override protected def clock() = localClock.get
      }
      val ttl = Ttl.fromPositive(5000.millis)
      val cacheEntry =
        DnsProtocol.Resolved(
          "test.local",
          immutable.Seq(ARecord("test.local", ttl, InetAddress.getByName("127.0.0.1"))))
      cache.put(("test.local", Ip()), cacheEntry, ttl)

      cache.cached(DnsProtocol.Resolve("test.local")) should ===(Some(cacheEntry))
      localClock.set(5000)
      cache.cached(DnsProtocol.Resolve("test.local")) should ===(None)
      localClock.set(0)
      cache.cached(DnsProtocol.Resolve("test.local")) should ===(Some(cacheEntry))
      localClock.set(5000)
      cache.cleanup()
      cache.cached(DnsProtocol.Resolve("test.local")) should ===(None)
      localClock.set(0)
      cache.cached(DnsProtocol.Resolve("test.local")) should ===(None)
    }

  }

  // TODO test that the old protocol is converted correctly
}
