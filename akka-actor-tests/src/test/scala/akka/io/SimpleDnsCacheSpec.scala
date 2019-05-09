/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

import akka.io.dns.CachePolicy.Ttl
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._

class SimpleDnsCacheSpec extends WordSpec with Matchers {
  "Cache" should {
    "not reply with expired but not yet swept out entries" in {
      val localClock = new AtomicLong(0)
      val cache: SimpleDnsCache = new SimpleDnsCache() {
        override protected def clock() = localClock.get
      }
      val cacheEntry = Dns.Resolved("test.local", Seq(InetAddress.getByName("127.0.0.1")))
      cache.put(cacheEntry, Ttl.fromPositive(5000.millis))

      cache.cached("test.local") should ===(Some(cacheEntry))
      localClock.set(4999)
      cache.cached("test.local") should ===(Some(cacheEntry))
      localClock.set(5000)
      cache.cached("test.local") should ===(None)
    }

    "sweep out expired entries on cleanup()" in {
      val localClock = new AtomicLong(0)
      val cache: SimpleDnsCache = new SimpleDnsCache() {
        override protected def clock() = localClock.get
      }
      val cacheEntry = Dns.Resolved("test.local", Seq(InetAddress.getByName("127.0.0.1")))
      cache.put(cacheEntry, Ttl.fromPositive(5000.millis))

      cache.cached("test.local") should ===(Some(cacheEntry))
      localClock.set(5000)
      cache.cached("test.local") should ===(None)
      localClock.set(0)
      cache.cached("test.local") should ===(Some(cacheEntry))
      localClock.set(5000)
      cache.cleanup()
      cache.cached("test.local") should ===(None)
      localClock.set(0)
      cache.cached("test.local") should ===(None)
    }
  }
}
