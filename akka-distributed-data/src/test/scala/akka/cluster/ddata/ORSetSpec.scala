/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import scala.collection.immutable.TreeMap

import akka.actor.Address
import akka.cluster.UniqueAddress
import akka.cluster.ddata.Replicator.Changed
import org.scalatest.Matchers
import org.scalatest.WordSpec

class ORSetSpec extends WordSpec with Matchers {

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)

  val nodeA = UniqueAddress(Address("akka.tcp", "Sys", "a", 2552), 1)
  val nodeB = UniqueAddress(nodeA.address.copy(host = Some("b")), 2)
  val nodeC = UniqueAddress(nodeA.address.copy(host = Some("c")), 3)
  val nodeD = UniqueAddress(nodeA.address.copy(host = Some("d")), 4)
  val nodeE = UniqueAddress(nodeA.address.copy(host = Some("e")), 5)
  val nodeF = UniqueAddress(nodeA.address.copy(host = Some("f")), 6)
  val nodeG = UniqueAddress(nodeA.address.copy(host = Some("g")), 7)
  val nodeH = UniqueAddress(nodeA.address.copy(host = Some("h")), 8)

  val user1 = """{"username":"john","password":"coltrane"}"""
  val user2 = """{"username":"sonny","password":"rollins"}"""
  val user3 = """{"username":"charlie","password":"parker"}"""
  val user4 = """{"username":"charles","password":"mingus"}"""

  "A ORSet" must {

    "be able to add user" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)

      val c4 = c3.add(node1, user4)
      val c5 = c4.add(node1, user3)

      c5.elements should contain(user1)
      c5.elements should contain(user2)
      c5.elements should contain(user3)
      c5.elements should contain(user4)
    }

    "be able to work with deltas" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c2a = c2.add(node1, user2)
      val c2aDelta = c2a.delta
      val c2b = c2a.resetDelta.remove(node1, user2)
      val c2bDelta = c2b.delta
      val c3 = c2b.add(node2, user2)

      val c3Delta = c3.delta

      val c4 = c3.resetDelta.add(node2, user3)

      val c4Delta = c4.delta

      val c5 = ORSet()

      val c6 = c5 merge c2aDelta merge c2bDelta merge c3Delta merge c4Delta

      c6.elements should contain(user1)
      c6.elements should contain(user2)
      c6.elements should contain(user3)
    }

    "be able to merge deltas independent of order" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c2a = c2.add(node1, user2)
      val c2aDelta = c2a.delta
      val c2b = c2a.resetDelta.remove(node1, user2)
      val c2bDelta = c2b.delta
      val c3 = c2b.add(node2, user2)

      val c3Delta = c3.delta

      val c4 = c3.resetDelta.add(node2, user3)

      val c4Delta = c4.delta

      val c5 = ORSet()

      val c6 = c5 merge c3Delta merge c2aDelta merge c4Delta merge c2bDelta

      c6.elements should contain(user1)
      c6.elements should contain(user2)
      c6.elements should contain(user3)
    }

    // not good enough test, improve :)
    "be able to coalesce deltas" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c2Delta = c2.delta

      val c2a = c2.resetDelta.add(node1, user2)
      val c2aDelta = c2a.delta
      val c2b = c2a.resetDelta.remove(node1, user2)
      val c2bDelta = c2b.delta
      val c3 = c2b.add(node2, user2)

      val c3Delta = c3.delta

      val c4 = c3.resetDelta.add(node2, user3)

      val c4Delta = c4.delta

      val c5 = ORSet()

      val addDelta = c3Delta merge c2aDelta

      val c6 = c5 merge c2Delta merge addDelta merge c2bDelta merge c4Delta

      c6.elements should contain(user1)
      c6.elements should contain(user2)
      c6.elements should contain(user3)
    }

    // not good enough test, improve :)
    "be able to produce the same result with deltas as with normal updates" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c2Delta = c2.delta

      val c2a = c2.resetDelta.add(node1, user2)
      val c2aDelta = c2a.delta
      val c2b = c2a.resetDelta.remove(node1, user2)
      val c2bDelta = c2b.delta
      val c3 = c2b.add(node2, user2)

      val c3Delta = c3.delta

      val c4 = c3.resetDelta.add(node2, user3)

      val c4Delta = c4.delta

      val c5 = ORSet()

      val addDelta = c3Delta merge c2aDelta

      val c6 = c5 merge c2Delta merge addDelta merge c2bDelta merge c4Delta

      val c7 = c5 merge c2Delta merge c2aDelta merge c3Delta merge c2bDelta merge c4Delta

      val c8 = c5 merge c2 merge c2a merge c3 merge c2b merge c4

      c6.elementsMap shouldEqual c7.elementsMap
      c6.vvector shouldEqual c7.vvector

      c6.elementsMap shouldEqual c8.elementsMap
      c6.vvector shouldEqual c8.vvector

      c6.elements should contain(user1)
      c6.elements should contain(user2)
      c6.elements should contain(user3)
    }

    // not good enough test, improve :)
    "not have holes due to out of order operations" in {
      val c1a = ORSet()

      val c1b = ORSet()

      val c2a = c1a.add(node1, user1)

      val c2aDelta = c2a.delta

      val c2b = c1b.add(node2, user1)

      val c2bDelta = c2b.delta

      val c3a = c2a.resetDelta.add(node1, user2)

      val c3aDelta = c3a.delta

      val c3b = c2b.resetDelta.add(node2, user2)

      val c3bDelta = c3b.delta

      val c4a = c3a.resetDelta.add(node1, user3)

      val c4aDelta = c4a.delta

      val c4b = c3b.resetDelta.add(node2, user3)

      val c4bDelta = c4b.delta

      val c5 = ORSet()

      // previous versions and skipped full updates do not delete
      val c6 = c5 merge c4b merge c3a

      c6.elements should contain(user1)
      c6.elements should contain(user2)
      c6.elements should contain(user3)

      val c7a = c5 merge c1a merge c2a merge c3a merge c4a

      val c8a = c5 merge c2aDelta merge c3aDelta merge c4aDelta

      val c7b = c5 merge c1b merge c2b merge c3b merge c4b

      val c8b = c5 merge c2bDelta merge c3bDelta merge c4bDelta

      c7a.elementsMap shouldEqual c8a.elementsMap

      c7b.elementsMap shouldEqual c8b.elementsMap

      // skipped deltas delete
      val deltaA = c5 merge c2aDelta merge c4bDelta merge c4aDelta
      val deltaB = c5 merge c2bDelta merge c3bDelta

      val c9 = deltaA merge deltaB

      c9.elements should contain(user1)
      //      c9.elements should contain(user2)
      c9.elements should contain(user3)

    }

    "be able to remove added user" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)

      val c4 = c3.remove(node1, user2)
      val c5 = c4.remove(node1, user1)

      c5.elements should not contain (user1)
      c5.elements should not contain (user2)

      val c6 = c3.merge(c5)
      c6.elements should not contain (user1)
      c6.elements should not contain (user2)

      val c7 = c5.merge(c3)
      c7.elements should not contain (user1)
      c7.elements should not contain (user2)
    }

    "be able to add removed" in {
      val c1 = ORSet()
      val c2 = c1.remove(node1, user1)
      val c3 = c2.add(node1, user1)
      c3.elements should contain(user1)
      val c4 = c3.remove(node1, user1)
      c4.elements should not contain (user1)
      val c5 = c4.add(node1, user1)
      c5.elements should contain(user1)
    }

    "be able to remove and add several times" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)
      val c4 = c3.remove(node1, user1)
      c4.elements should not contain (user1)
      c4.elements should contain(user2)

      val c5 = c4.add(node1, user1)
      val c6 = c5.add(node1, user2)
      c6.elements should contain(user1)
      c6.elements should contain(user2)

      val c7 = c6.remove(node1, user1)
      val c8 = c7.add(node1, user2)
      val c9 = c8.remove(node1, user1)
      c9.elements should not contain (user1)
      c9.elements should contain(user2)
    }

    "be able to have its user set correctly merged with another ORSet with unique user sets" in {
      // set 1
      val c1 = ORSet().add(node1, user1).add(node1, user2)
      c1.elements should contain(user1)
      c1.elements should contain(user2)

      // set 2
      val c2 = ORSet().add(node2, user3).add(node2, user4).remove(node2, user3)

      c2.elements should not contain (user3)
      c2.elements should contain(user4)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.elements should contain(user1)
      merged1.elements should contain(user2)
      merged1.elements should not contain (user3)
      merged1.elements should contain(user4)

      val merged2 = c2 merge c1
      merged2.elements should contain(user1)
      merged2.elements should contain(user2)
      merged2.elements should not contain (user3)
      merged2.elements should contain(user4)
    }

    "be able to have its user set correctly merged with another ORSet with overlapping user sets" in {
      // set 1
      val c1 = ORSet().add(node1, user1).add(node1, user2).add(node1, user3).remove(node1, user1).remove(node1, user3)

      c1.elements should not contain (user1)
      c1.elements should contain(user2)
      c1.elements should not contain (user3)

      // set 2
      val c2 = ORSet().add(node2, user1).add(node2, user2).add(node2, user3).add(node2, user4).remove(node2, user3)

      c2.elements should contain(user1)
      c2.elements should contain(user2)
      c2.elements should not contain (user3)
      c2.elements should contain(user4)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.elements should contain(user1)
      merged1.elements should contain(user2)
      merged1.elements should not contain (user3)
      merged1.elements should contain(user4)

      val merged2 = c2 merge c1
      merged2.elements should contain(user1)
      merged2.elements should contain(user2)
      merged2.elements should not contain (user3)
      merged2.elements should contain(user4)
    }

    "be able to have its user set correctly merged for concurrent updates" in {
      val c1 = ORSet().add(node1, user1).add(node1, user2).add(node1, user3)

      c1.elements should contain(user1)
      c1.elements should contain(user2)
      c1.elements should contain(user3)

      val c2 = c1.add(node2, user1).remove(node2, user2).remove(node2, user3)

      c2.elements should contain(user1)
      c2.elements should not contain (user2)
      c2.elements should not contain (user3)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.elements should contain(user1)
      merged1.elements should not contain (user2)
      merged1.elements should not contain (user3)

      val merged2 = c2 merge c1
      merged2.elements should contain(user1)
      merged2.elements should not contain (user2)
      merged2.elements should not contain (user3)

      val c3 = c1.add(node1, user4).remove(node1, user3).add(node1, user2)

      // merge both ways
      val merged3 = c2 merge c3
      merged3.elements should contain(user1)
      merged3.elements should contain(user2)
      merged3.elements should not contain (user3)
      merged3.elements should contain(user4)

      val merged4 = c3 merge c2
      merged4.elements should contain(user1)
      merged4.elements should contain(user2)
      merged4.elements should not contain (user3)
      merged4.elements should contain(user4)
    }

    "be able to have its user set correctly merged after remove" in {
      val c1 = ORSet().add(node1, user1).add(node1, user2)
      val c2 = c1.remove(node2, user2)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.elements should contain(user1)
      merged1.elements should not contain (user2)

      val merged2 = c2 merge c1
      merged2.elements should contain(user1)
      merged2.elements should not contain (user2)

      val c3 = c1.add(node1, user3)

      // merge both ways
      val merged3 = c3 merge c2
      merged3.elements should contain(user1)
      merged3.elements should not contain (user2)
      merged3.elements should contain(user3)

      val merged4 = c2 merge c3
      merged4.elements should contain(user1)
      merged4.elements should not contain (user2)
      merged4.elements should contain(user3)
    }

  }

  "ORSet unit test" must {
    "verify subtractDots" in {
      val dot = VersionVector(TreeMap(nodeA → 3L, nodeB → 2L, nodeD → 14L, nodeG → 22L))
      val vvector = VersionVector(TreeMap(nodeA → 4L, nodeB → 1L, nodeC → 1L, nodeD → 14L, nodeE → 5L, nodeF → 2L))
      val expected = VersionVector(TreeMap(nodeB → 2L, nodeG → 22L))
      ORSet.subtractDots(dot, vvector) should be(expected)
    }

    "verify mergeCommonKeys" in {
      val commonKeys: Set[String] = Set("K1", "K2")
      val thisDot1 = VersionVector(TreeMap(nodeA → 3L, nodeD → 7L))
      val thisDot2 = VersionVector(TreeMap(nodeB → 5L, nodeC → 2L))
      val thisVvector = VersionVector(TreeMap(nodeA → 3L, nodeB → 5L, nodeC → 2L, nodeD → 7L))
      val thisSet = new ORSet(
        elementsMap = Map("K1" → thisDot1, "K2" → thisDot2),
        vvector = thisVvector)
      val thatDot1 = VersionVector(nodeA, 3L)
      val thatDot2 = VersionVector(nodeB, 6L)
      val thatVvector = VersionVector(TreeMap(nodeA → 3L, nodeB → 6L, nodeC → 1L, nodeD → 8L))
      val thatSet = new ORSet(
        elementsMap = Map("K1" → thatDot1, "K2" → thatDot2),
        vvector = thatVvector)

      val expectedDots = Map(
        "K1" → VersionVector(nodeA, 3L),
        "K2" → VersionVector(TreeMap(nodeB → 6L, nodeC → 2L)))

      ORSet.mergeCommonKeys(commonKeys, thisSet, thatSet) should be(expectedDots)
    }

    "verify mergeDisjointKeys" in {
      val keys: Set[Any] = Set("K3", "K4", "K5")
      val elements: Map[Any, VersionVector] = Map(
        "K3" → VersionVector(nodeA, 4L),
        "K4" → VersionVector(TreeMap(nodeA → 3L, nodeD → 8L)),
        "K5" → VersionVector(nodeA, 2L))
      val vvector = VersionVector(TreeMap(nodeA → 3L, nodeD → 7L))
      val acc: Map[Any, VersionVector] = Map("K1" → VersionVector(nodeA, 3L))
      val expectedDots = acc ++ Map(
        "K3" → VersionVector(nodeA, 4L),
        "K4" → VersionVector(nodeD, 8L)) // "a" -> 3 removed, optimized to include only those unseen

      ORSet.mergeDisjointKeys(keys, elements, vvector, acc) should be(expectedDots)
    }

    "verify disjoint merge" in {
      val a1 = ORSet().add(node1, "bar")
      val b1 = ORSet().add(node2, "baz")
      val c = a1.merge(b1)
      val a2 = a1.remove(node1, "bar")
      val d = a2.merge(c)
      d.elements should be(Set("baz"))
    }

    "verify removed after merge" in {
      // Add Z at node1 replica
      val a = ORSet().add(node1, "Z")
      // Replicate it to some node3, i.e. it has dot 'Z'->{node1 -> 1}
      val c = a
      // Remove Z at node1 replica
      val a2 = a.remove(node1, "Z")
      // Add Z at node2, a new replica
      val b = ORSet().add(node2, "Z")
      // Replicate b to node1, so now node1 has a Z, the one with a Dot of
      // {node2 -> 1} and version vector of [{node1 -> 1}, {node2 -> 1}]
      val a3 = b.merge(a2)
      a3.elements should be(Set("Z"))
      // Remove the 'Z' at node2 replica
      val b2 = b.remove(node2, "Z")
      // Both node3 (c) and node1 (a3) have a 'Z', but when they merge, there should be
      // no 'Z' as node3 (c)'s has been removed by node1 and node1 (a3)'s has been removed by
      // node2
      c.elements should be(Set("Z"))
      a3.elements should be(Set("Z"))
      b2.elements should be(Set())

      a3.merge(c).merge(b2).elements should be(Set.empty)
      a3.merge(b2).merge(c).elements should be(Set.empty)
      c.merge(b2).merge(a3).elements should be(Set.empty)
      c.merge(a3).merge(b2).elements should be(Set.empty)
      b2.merge(c).merge(a3).elements should be(Set.empty)
      b2.merge(a3).merge(c).elements should be(Set.empty)
    }

    "verify removed after merge 2" in {
      val a = ORSet().add(node1, "Z")
      val b = ORSet().add(node2, "Z")
      // replicate node3
      val c = a
      val a2 = a.remove(node1, "Z")
      // replicate b to node1, now node1 has node2's 'Z'
      val a3 = a2.merge(b)
      a3.elements should be(Set("Z"))
      // Remove node2's 'Z'
      val b2 = b.remove(node2, "Z")
      // Replicate c to node2, now node2 has node1's old 'Z'
      val b3 = b2.merge(c)
      b3.elements should be(Set("Z"))
      // Merge everytyhing
      a3.merge(c).merge(b3).elements should be(Set.empty)
      a3.merge(b3).merge(c).elements should be(Set.empty)
      c.merge(b3).merge(a3).elements should be(Set.empty)
      c.merge(a3).merge(b3).elements should be(Set.empty)
      b3.merge(c).merge(a3).elements should be(Set.empty)
      b3.merge(a3).merge(c).elements should be(Set.empty)
    }

    "have unapply extractor" in {
      val s1 = ORSet.empty.add(node1, "a").add(node2, "b")
      val s2: ORSet[String] = s1
      val ORSet(elements1) = s1 // `unapply[A](s: ORSet[A])` is used here
      val elements2: Set[String] = elements1

      Changed(ORSetKey[String]("key"))(s1) match {
        case c @ Changed(ORSetKey("key")) ⇒
          val x: ORSet[String] = c.dataValue
          val ORSet(elements3) = c.dataValue
          val elements4: Set[String] = elements3
          elements4 should be(Set("a", "b"))
      }

      val msg: Any = Changed(ORSetKey[String]("key"))(s1)
      msg match {
        case c @ Changed(ORSetKey("key")) ⇒
          val ORSet(elements3) = c.dataValue // `unapply(a: ReplicatedData)` is used here
          // if `unapply(a: ReplicatedData)` isn't defined the next line doesn't compile:
          //   type mismatch; found : scala.collection.immutable.Set[A] where type A required: Set[Any] Note: A <: Any,
          //   but trait Set is invariant in type A. You may wish to investigate a wildcard type such as _ <: Any. (SLS 3.2.10)
          val elements4: Set[Any] = elements3
          elements4 should be(Set("a", "b"))
      }
    }

  }
}
