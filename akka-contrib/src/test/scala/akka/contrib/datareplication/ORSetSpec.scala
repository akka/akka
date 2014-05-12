/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.datareplication

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.cluster.UniqueAddress
import akka.actor.Address
import akka.cluster.VectorClock
import scala.collection.immutable.TreeMap

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ORSetSpec extends WordSpec with Matchers {

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)

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

      c5.value should contain(user1)
      c5.value should contain(user2)
      c5.value should contain(user3)
      c5.value should contain(user4)
    }

    "be able to remove added user" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)

      val c4 = c3.remove(node1, user2)
      val c5 = c4.remove(node1, user1)

      c5.value should not contain (user1)
      c5.value should not contain (user2)
    }

    "be able to add removed" in {
      val c1 = ORSet()
      val c2 = c1.remove(node1, user1)
      val c3 = c2.add(node1, user1)
      c3.value should contain(user1)
      val c4 = c3.remove(node1, user1)
      c4.value should not contain (user1)
      val c5 = c4.add(node1, user1)
      c5.value should contain(user1)
    }

    "be able to remove and add several times" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)
      val c4 = c3.remove(node1, user1)
      c4.value should not contain (user1)
      c4.value should contain(user2)

      val c5 = c4.add(node1, user1)
      val c6 = c5.add(node1, user2)
      c6.value should contain(user1)
      c6.value should contain(user2)

      val c7 = c6.remove(node1, user1)
      val c8 = c7.add(node1, user2)
      val c9 = c8.remove(node1, user1)
      c9.value should not contain (user1)
      c9.value should contain(user2)
    }

    "be able to have its user set correctly merged with another ORSet with unique user sets" in {
      // set 1
      val c1 = ORSet().add(node1, user1).add(node1, user2)
      c1.value should contain(user1)
      c1.value should contain(user2)

      // set 2
      val c2 = ORSet().add(node2, user3).add(node2, user4).remove(node2, user3)

      c2.value should not contain (user3)
      c2.value should contain(user4)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.value should contain(user1)
      merged1.value should contain(user2)
      merged1.value should not contain (user3)
      merged1.value should contain(user4)

      val merged2 = c2 merge c1
      merged2.value should contain(user1)
      merged2.value should contain(user2)
      merged2.value should not contain (user3)
      merged2.value should contain(user4)
    }

    "be able to have its user set correctly merged with another ORSet with overlapping user sets" in {
      // set 1
      val c1 = ORSet().add(node1, user1).add(node1, user2).add(node1, user3).remove(node1, user1).remove(node1, user3)

      c1.value should not contain (user1)
      c1.value should contain(user2)
      c1.value should not contain (user3)

      // set 2
      val c2 = ORSet().add(node2, user1).add(node2, user2).add(node2, user3).add(node2, user4).remove(node2, user3)

      c2.value should contain(user1)
      c2.value should contain(user2)
      c2.value should not contain (user3)
      c2.value should contain(user4)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.value should contain(user1)
      merged1.value should contain(user2)
      merged1.value should not contain (user3)
      merged1.value should contain(user4)

      val merged2 = c2 merge c1
      merged2.value should contain(user1)
      merged2.value should contain(user2)
      merged2.value should not contain (user3)
      merged2.value should contain(user4)
    }

    "be able to have its user set correctly merged for concurrent updates" in {
      val c1 = ORSet().add(node1, user1).add(node1, user2).add(node1, user3)

      c1.value should contain(user1)
      c1.value should contain(user2)
      c1.value should contain(user3)

      val c2 = c1.add(node2, user1).remove(node2, user2).remove(node2, user3)

      c2.value should contain(user1)
      c2.value should not contain (user2)
      c2.value should not contain (user3)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.value should contain(user1)
      merged1.value should not contain (user2)
      merged1.value should not contain (user3)

      val merged2 = c2 merge c1
      merged2.value should contain(user1)
      merged2.value should not contain (user2)
      merged2.value should not contain (user3)

      val c3 = c1.add(node1, user4).remove(node1, user3).add(node1, user2)

      // merge both ways
      val merged3 = c2 merge c3
      merged3.value should contain(user1)
      merged3.value should contain(user2)
      merged3.value should not contain (user3)
      merged3.value should contain(user4)

      val merged4 = c3 merge c2
      merged4.value should contain(user1)
      merged4.value should contain(user2)
      merged4.value should not contain (user3)
      merged4.value should contain(user4)
    }

    "be able to have its user set correctly merged after remove" in {
      val c1 = ORSet().add(node1, user1).add(node1, user2)
      val c2 = c1.remove(node2, user2)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.value should contain(user1)
      merged1.value should not contain (user2)

      val merged2 = c2 merge c1
      merged2.value should contain(user1)
      merged2.value should not contain (user2)

      val c3 = c1.add(node1, user3)

      // merge both ways
      val merged3 = c3 merge c2
      merged3.value should contain(user1)
      merged3.value should not contain (user2)
      merged3.value should contain(user3)

      val merged4 = c2 merge c3
      merged4.value should contain(user1)
      merged4.value should not contain (user2)
      merged4.value should contain(user3)
    }

  }

  "ORSet unit test" must {
    "verify subtractDots" in {
      val dot = new VectorClock(TreeMap("a" -> 3, "b" -> 2, "d" -> 14, "g" -> 22))
      val vclock = new VectorClock(TreeMap("a" -> 4, "b" -> 1, "c" -> 1, "d" -> 14, "e" -> 5, "f" -> 2))
      val expected = new VectorClock(TreeMap("b" -> 2, "g" -> 22))
      ORSet.subtractDots(dot, vclock) should be(expected)
    }

    "verify mergeCommonKeys" in {
      val commonKeys: Set[Any] = Set("K1", "K2")
      val thisDot1 = new VectorClock(TreeMap("a" -> 3, "d" -> 7))
      val thisDot2 = new VectorClock(TreeMap("b" -> 5, "c" -> 2))
      val thisVclock = new VectorClock(TreeMap("a" -> 3, "b" -> 5, "c" -> 2, "d" -> 7))
      val thisSet = new ORSet(
        elements = Map("K1" -> thisDot1, "K2" -> thisDot2),
        vclock = thisVclock)
      val thatDot1 = new VectorClock(TreeMap("a" -> 3))
      val thatDot2 = new VectorClock(TreeMap("b" -> 6))
      val thatVclock = new VectorClock(TreeMap("a" -> 3, "b" -> 6, "c" -> 1, "d" -> 8))
      val thatSet = new ORSet(
        elements = Map("K1" -> thatDot1, "K2" -> thatDot2),
        vclock = thatVclock)

      val expectedDots = Map(
        "K1" -> new VectorClock(TreeMap("a" -> 3)),
        "K2" -> new VectorClock(TreeMap("b" -> 6, "c" -> 2)))

      ORSet.mergeCommonKeys(commonKeys, thisSet, thatSet) should be(expectedDots)
    }

    "verify mergeDisjointKeys" in {
      val keys: Set[Any] = Set("K3", "K4", "K5")
      val elements: Map[Any, VectorClock] = Map(
        "K3" -> new VectorClock(TreeMap("a" -> 4)),
        "K4" -> new VectorClock(TreeMap("a" -> 3, "d" -> 8)),
        "K5" -> new VectorClock(TreeMap("a" -> 2)))
      val vclock = new VectorClock(TreeMap("a" -> 3, "d" -> 7))
      val acc: Map[Any, VectorClock] = Map("K1" -> new VectorClock(TreeMap("a" -> 3)))
      val expectedDots = acc ++ Map(
        "K3" -> new VectorClock(TreeMap("a" -> 4)),
        "K4" -> new VectorClock(TreeMap("d" -> 8))) // "a" -> 3 removed, optimized to include only those unseen

      ORSet.mergeDisjointKeys(keys, elements, vclock, acc) should be(expectedDots)
    }

    "verify disjoint merge" in {
      val a1 = ORSet().add(node1, "bar")
      val b1 = ORSet().add(node2, "baz")
      val c = a1.merge(b1)
      val a2 = a1.remove(node1, "bar")
      val d = a2.merge(c)
      d.value should be(Set("baz"))
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
      // {node2 -> 1} and clock of [{node1 -> 1}, {node2 -> 1}]
      val a3 = b.merge(a2)
      a3.value should be(Set("Z"))
      // Remove the 'Z' at node2 replica
      val b2 = b.remove(node2, "Z")
      // Both node3 (c) and node1 (a3) have a 'Z', but when they merge, there should be
      // no 'Z' as node3 (c)'s has been removed by node1 and node1 (a3)'s has been removed by
      // node2
      c.value should be(Set("Z"))
      a3.value should be(Set("Z"))
      b2.value should be(Set())

      a3.merge(c).merge(b2).value should be(Set.empty)
      a3.merge(b2).merge(c).value should be(Set.empty)
      c.merge(b2).merge(a3).value should be(Set.empty)
      c.merge(a3).merge(b2).value should be(Set.empty)
      b2.merge(c).merge(a3).value should be(Set.empty)
      b2.merge(a3).merge(c).value should be(Set.empty)
    }

    "verify removed after merge 2" in {
      val a = ORSet().add(node1, "Z")
      val b = ORSet().add(node2, "Z")
      // replicate node3
      val c = a
      val a2 = a.remove(node1, "Z")
      // replicate b to node1, now node1 has node2's 'Z'
      val a3 = a2.merge(b)
      a3.value should be(Set("Z"))
      // Remove node2's 'Z'
      val b2 = b.remove(node2, "Z")
      // Replicate c to node2, now node2 has node1's old 'Z'
      val b3 = b2.merge(c)
      b3.value should be(Set("Z"))
      // Merge everytyhing
      a3.merge(c).merge(b3).value should be(Set.empty)
      a3.merge(b3).merge(c).value should be(Set.empty)
      c.merge(b3).merge(a3).value should be(Set.empty)
      c.merge(a3).merge(b3).value should be(Set.empty)
      b3.merge(c).merge(a3).value should be(Set.empty)
      b3.merge(a3).merge(c).value should be(Set.empty)
    }

  }
}
