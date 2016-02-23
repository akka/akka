/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.UniqueAddress
import akka.cluster.ddata.Replicator.Changed
import org.scalatest.Matchers
import org.scalatest.WordSpec

class ORMapSpec extends WordSpec with Matchers {

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)

  "A ORMap" must {

    "be able to add entries" in {
      val m = ORMap().put(node1, "a", GSet() + "A").put(node1, "b", GSet() + "B")
      val GSet(a) = m.entries("a")
      a should be(Set("A"))
      val GSet(b) = m.entries("b")
      b should be(Set("B"))

      val m2 = m.put(node1, "a", GSet() + "C")
      val GSet(a2) = m2.entries("a")
      a2 should be(Set("C"))

    }

    "be able to remove entry" in {
      val m = ORMap().put(node1, "a", GSet() + "A").put(node1, "b", GSet() + "B").remove(node1, "a")
      m.entries.keySet should not contain ("a")
      m.entries.keySet should contain("b")
    }

    "be able to add removed" in {
      val m = ORMap().put(node1, "a", GSet() + "A").put(node1, "b", GSet() + "B").remove(node1, "a")
      m.entries.keySet should not contain ("a")
      m.entries.keySet should contain("b")
      val m2 = m.put(node1, "a", GSet() + "C")
      m2.entries.keySet should contain("a")
      m2.entries.keySet should contain("b")
    }

    "be able to have its entries correctly merged with another ORMap with other entries" in {
      val m1 = ORMap().put(node1, "a", GSet() + "A").put(node1, "b", GSet() + "B")
      val m2 = ORMap().put(node2, "c", GSet() + "C")

      // merge both ways
      val merged1 = m1 merge m2
      merged1.entries.keySet should contain("a")
      merged1.entries.keySet should contain("b")
      merged1.entries.keySet should contain("c")

      val merged2 = m2 merge m1
      merged2.entries.keySet should contain("a")
      merged2.entries.keySet should contain("b")
      merged2.entries.keySet should contain("c")
    }

    "be able to have its entries correctly merged with another ORMap with overlapping entries" in {
      val m1 = ORMap().put(node1, "a", GSet() + "A1").put(node1, "b", GSet() + "B1").
        remove(node1, "a").put(node1, "d", GSet() + "D1")
      val m2 = ORMap().put(node2, "c", GSet() + "C2").put(node2, "a", GSet() + "A2").
        put(node2, "b", GSet() + "B2").remove(node2, "b").put(node2, "d", GSet() + "D2")

      // merge both ways
      val merged1 = m1 merge m2
      merged1.entries.keySet should contain("a")
      val GSet(a1) = merged1.entries("a")
      a1 should be(Set("A2"))
      merged1.entries.keySet should contain("b")
      val GSet(b1) = merged1.entries("b")
      b1 should be(Set("B1"))
      merged1.entries.keySet should contain("c")
      merged1.entries.keySet should contain("d")
      val GSet(d1) = merged1.entries("d")
      d1 should be(Set("D1", "D2"))

      val merged2 = m2 merge m1
      merged2.entries.keySet should contain("a")
      val GSet(a2) = merged1.entries("a")
      a2 should be(Set("A2"))
      merged2.entries.keySet should contain("b")
      val GSet(b2) = merged2.entries("b")
      b2 should be(Set("B1"))
      merged2.entries.keySet should contain("c")
      merged2.entries.keySet should contain("d")
      val GSet(d2) = merged2.entries("d")
      d2 should be(Set("D1", "D2"))
    }

    "illustrate the danger of using remove+put to replace an entry" in {
      val m1 = ORMap.empty.put(node1, "a", GSet.empty + "A").put(node1, "b", GSet.empty + "B")
      val m2 = ORMap.empty.put(node2, "c", GSet.empty + "C")

      val merged1 = m1 merge m2

      val m3 = merged1.remove(node1, "b").put(node1, "b", GSet.empty + "B2")
      // same thing if only put is used
      //      val m3 = merged1.put(node1, "b", GSet.empty + "B2")
      val merged2 = merged1 merge m3

      merged2.entries("a").elements should be(Set("A"))
      // note that B is included, because GSet("B") is merged with GSet("B2")
      merged2.entries("b").elements should be(Set("B", "B2"))
      merged2.entries("c").elements should be(Set("C"))
    }

    "not allow put for ORSet elements type" in {
      val m = ORMap().put(node1, "a", ORSet().add(node1, "A"))

      intercept[IllegalArgumentException] {
        m.put(node1, "a", ORSet().add(node1, "B"))
      }
    }

    "be able to update entry" in {
      val m1 = ORMap.empty[ORSet[String]].put(node1, "a", ORSet.empty.add(node1, "A"))
        .put(node1, "b", ORSet.empty.add(node1, "B01").add(node1, "B02").add(node1, "B03"))
      val m2 = ORMap.empty[ORSet[String]].put(node2, "c", ORSet.empty.add(node2, "C"))

      val merged1: ORMap[ORSet[String]] = m1 merge m2

      val m3 = merged1.updated(node1, "b", ORSet.empty[String])(_.clear(node1).add(node1, "B2"))

      val merged2 = merged1 merge m3
      merged2.entries("a").elements should be(Set("A"))
      merged2.entries("b").elements should be(Set("B2"))
      merged2.entries("c").elements should be(Set("C"))

      val m4 = merged1.updated(node2, "b", ORSet.empty[String])(_.add(node2, "B3"))
      val merged3 = m3 merge m4
      merged3.entries("a").elements should be(Set("A"))
      merged3.entries("b").elements should be(Set("B2", "B3"))
      merged3.entries("c").elements should be(Set("C"))
    }

    "be able to update ORSet entry with remove+put" in {
      val m1 = ORMap.empty[ORSet[String]].put(node1, "a", ORSet.empty.add(node1, "A01"))
        .updated(node1, "a", ORSet.empty[String])(_.add(node1, "A02"))
        .updated(node1, "a", ORSet.empty[String])(_.add(node1, "A03"))
        .put(node1, "b", ORSet.empty.add(node1, "B01").add(node1, "B02").add(node1, "B03"))
      val m2 = ORMap.empty[ORSet[String]].put(node2, "c", ORSet.empty.add(node2, "C"))

      val merged1 = m1 merge m2

      // note that remove + put work because the new VersionVector version is incremented
      // from a global counter
      val m3 = merged1.remove(node1, "b").put(node1, "b", ORSet.empty.add(node1, "B2"))

      val merged2 = merged1 merge m3
      merged2.entries("a").elements should be(Set("A01", "A02", "A03"))
      merged2.entries("b").elements should be(Set("B2"))
      merged2.entries("c").elements should be(Set("C"))

      val m4 = merged1.updated(node2, "b", ORSet.empty[String])(_.add(node2, "B3"))
      val merged3 = m3 merge m4
      merged3.entries("a").elements should be(Set("A01", "A02", "A03"))
      merged3.entries("b").elements should be(Set("B2", "B3"))
      merged3.entries("c").elements should be(Set("C"))
    }

    "be able to update ORSet entry with remove -> merge -> put" in {
      val m1 = ORMap.empty.put(node1, "a", ORSet.empty.add(node1, "A"))
        .put(node1, "b", ORSet.empty.add(node1, "B01").add(node1, "B02").add(node1, "B03"))
      val m2 = ORMap.empty.put(node2, "c", ORSet.empty.add(node2, "C"))

      val merged1 = m1 merge m2

      val m3 = merged1.remove(node1, "b")

      val merged2 = merged1 merge m3
      merged2.entries("a").elements should be(Set("A"))
      merged2.contains("b") should be(false)
      merged2.entries("c").elements should be(Set("C"))

      val m4 = merged2.put(node1, "b", ORSet.empty.add(node1, "B2"))
      val m5 = merged2.updated(node2, "c", ORSet.empty[String])(_.add(node2, "C2"))
        .put(node2, "b", ORSet.empty.add(node2, "B3"))

      val merged3 = m5 merge m4
      merged3.entries("a").elements should be(Set("A"))
      merged3.entries("b").elements should be(Set("B2", "B3"))
      merged3.entries("c").elements should be(Set("C", "C2"))
    }

    "have unapply extractor" in {
      val m1 = ORMap.empty.put(node1, "a", Flag(true)).put(node2, "b", Flag(false))
      val m2: ORMap[Flag] = m1
      val ORMap(entries1) = m1
      val entries2: Map[String, Flag] = entries1
      Changed(ORMapKey[Flag]("key"))(m1) match {
        case c @ Changed(ORMapKey("key")) ⇒
          val ORMap(entries3) = c.dataValue
          val entries4: Map[String, ReplicatedData] = entries3
          entries4 should be(Map("a" -> Flag(true), "b" -> Flag(false)))
      }
    }

  }
}
