/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.UniqueAddress
import akka.cluster.ddata.Replicator.Changed
import org.scalatest.Matchers
import org.scalatest.WordSpec

class LWWMapSpec extends WordSpec with Matchers {
  import LWWRegister.defaultClock

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)

  "A LWWMap" must {

    "be able to set entries" in {
      val m = LWWMap.empty[Int].put(node1, "a", 1, defaultClock[Int]).put(node2, "b", 2, defaultClock[Int])
      m.entries should be(Map("a" -> 1, "b" -> 2))
    }

    "be able to have its entries correctly merged with another LWWMap with other entries" in {
      val m1 = LWWMap.empty.put(node1, "a", 1, defaultClock[Int]).put(node1, "b", 2, defaultClock[Int])
      val m2 = LWWMap.empty.put(node2, "c", 3, defaultClock[Int])

      // merge both ways
      val expected = Map("a" -> 1, "b" -> 2, "c" -> 3)
      (m1 merge m2).entries should be(expected)
      (m2 merge m1).entries should be(expected)
    }

    "be able to remove entry" in {
      val m1 = LWWMap.empty.put(node1, "a", 1, defaultClock[Int]).put(node1, "b", 2, defaultClock[Int])
      val m2 = LWWMap.empty.put(node2, "c", 3, defaultClock[Int])

      val merged1 = m1 merge m2

      val m3 = merged1.remove(node1, "b")
      (merged1 merge m3).entries should be(Map("a" -> 1, "c" -> 3))

      // but if there is a conflicting update the entry is not removed
      val m4 = merged1.put(node2, "b", 22, defaultClock[Int])
      (m3 merge m4).entries should be(Map("a" -> 1, "b" -> 22, "c" -> 3))
    }

    "have unapply extractor" in {
      val m1 = LWWMap.empty.put(node1, "a", 1L, defaultClock[Long])
      val LWWMap(entries1) = m1
      val entries2: Map[String, Long] = entries1
      Changed(LWWMapKey[Long]("key"))(m1) match {
        case c @ Changed(LWWMapKey("key")) ⇒
          val LWWMap(entries3) = c.dataValue
          val entries4: Map[String, Long] = entries3
          entries4 should be(Map("a" -> 1L))
      }
    }

  }
}
