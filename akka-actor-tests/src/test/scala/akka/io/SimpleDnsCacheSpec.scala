package akka.io

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

import org.scalatest.{ ShouldMatchers, WordSpec }

class SimpleDnsCacheSpec extends WordSpec with ShouldMatchers {
  "Cache" should {
    "not reply with expired but not yet swept out entries" in {
      val localClock = new AtomicLong(0)
      val cache: SimpleDnsCache = new SimpleDnsCache() {
        override protected def clock() = localClock.get
      }
      val cacheEntry = Dns.Resolved("test.local", Seq(InetAddress.getByName("127.0.0.1")))
      cache.put(cacheEntry, 5000)

      cache.cached("test.local") should equal(Some(cacheEntry))
      localClock.set(4999)
      cache.cached("test.local") should equal(Some(cacheEntry))
      localClock.set(5000)
      cache.cached("test.local") should equal(None)
    }

    "sweep out expired entries on cleanup()" in {
      val localClock = new AtomicLong(0)
      val cache: SimpleDnsCache = new SimpleDnsCache() {
        override protected def clock() = localClock.get
      }
      val cacheEntry = Dns.Resolved("test.local", Seq(InetAddress.getByName("127.0.0.1")))
      cache.put(cacheEntry, 5000)

      cache.cached("test.local") should equal(Some(cacheEntry))
      localClock.set(5000)
      cache.cached("test.local") should equal(None)
      localClock.set(0)
      cache.cached("test.local") should equal(Some(cacheEntry))
      localClock.set(5000)
      cache.cleanup()
      cache.cached("test.local") should equal(None)
      localClock.set(0)
      cache.cached("test.local") should equal(None)
    }
  }
}
