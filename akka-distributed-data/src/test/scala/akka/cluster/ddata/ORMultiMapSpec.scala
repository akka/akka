/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.UniqueAddress
import akka.cluster.ddata.Replicator.Changed
import org.scalatest.{ Matchers, WordSpec }

class ORMultiMapSpec extends WordSpec with Matchers {

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)

  "A ORMultiMap" must {

    "be able to add entries" in {
      val m = ORMultiMap().addBinding(node1, "a", "A").addBinding(node1, "b", "B")
      m.entries should be(Map("a" → Set("A"), "b" → Set("B")))

      val m2 = m.addBinding(node1, "a", "C")
      m2.entries should be(Map("a" → Set("A", "C"), "b" → Set("B")))
    }

    "be able to remove entry" in {
      val m = ORMultiMap().addBinding(node1, "a", "A").addBinding(node1, "b", "B").removeBinding(node1, "a", "A")
      m.entries should be(Map("b" → Set("B")))
    }

    "be able to replace an entry" in {
      val m = ORMultiMap().addBinding(node1, "a", "A").replaceBinding(node1, "a", "A", "B")
      m.entries should be(Map("a" → Set("B")))
    }

    "be able to have its entries correctly merged with another ORMultiMap with other entries" in {
      val m1 = ORMultiMap().addBinding(node1, "a", "A").addBinding(node1, "b", "B")
      val m2 = ORMultiMap().addBinding(node2, "c", "C")

      // merge both ways

      val expectedMerge = Map(
        "a" → Set("A"),
        "b" → Set("B"),
        "c" → Set("C"))

      val merged1 = m1 merge m2
      merged1.entries should be(expectedMerge)

      val merged2 = m2 merge m1
      merged2.entries should be(expectedMerge)
    }

    "be able to have its entries correctly merged with another ORMultiMap with overlapping entries" in {
      val m1 = ORMultiMap()
        .addBinding(node1, "a", "A1")
        .addBinding(node1, "b", "B1")
        .removeBinding(node1, "a", "A1")
        .addBinding(node1, "d", "D1")
      val m2 = ORMultiMap()
        .addBinding(node2, "c", "C2")
        .addBinding(node2, "a", "A2")
        .addBinding(node2, "b", "B2")
        .removeBinding(node2, "b", "B2")
        .addBinding(node2, "d", "D2")

      // merge both ways

      val expectedMerged = Map(
        "a" → Set("A2"),
        "b" → Set("B1"),
        "c" → Set("C2"),
        "d" → Set("D1", "D2"))

      val merged1 = m1 merge m2
      merged1.entries should be(expectedMerged)

      val merged2 = m2 merge m1
      merged2.entries should be(expectedMerged)
    }
  }

  "be able to get all bindings for an entry and then reduce them upon putting them back" in {
    val m = ORMultiMap().addBinding(node1, "a", "A1").addBinding(node1, "a", "A2").addBinding(node1, "b", "B1")
    val Some(a) = m.get("a")

    a should be(Set("A1", "A2"))

    val m2 = m.put(node1, "a", a - "A1")

    val expectedMerged = Map(
      "a" → Set("A2"),
      "b" → Set("B1"))

    m2.entries should be(expectedMerged)
  }

  "return the value for an existing key and the default for a non-existing one when using getOrElse" in {
    val m = ORMultiMap().addBinding(node1, "a", "A")
    m.getOrElse("a", Set("B")) shouldBe Set("A")
    m.getOrElse("b", Set("B")) shouldBe Set("B")
  }

  "remove all bindings for a given key" in {
    val m = ORMultiMap().addBinding(node1, "a", "A1").addBinding(node1, "a", "A2").addBinding(node1, "b", "B1")
    val m2 = m.remove(node1, "a")
    m2.entries should be(Map("b" → Set("B1")))
  }

  "have unapply extractor" in {
    val m1 = ORMultiMap.empty.put(node1, "a", Set(1L, 2L)).put(node2, "b", Set(3L))
    val m2: ORMultiMap[Long] = m1
    val ORMultiMap(entries1) = m1
    val entries2: Map[String, Set[Long]] = entries1
    Changed(ORMultiMapKey[Long]("key"))(m1) match {
      case c @ Changed(ORMultiMapKey("key")) ⇒
        val ORMultiMap(entries3) = c.dataValue
        val entries4: Map[String, Set[Long]] = entries3
        entries4 should be(Map("a" → Set(1L, 2L), "b" → Set(3L)))
    }
  }
}
