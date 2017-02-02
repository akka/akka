/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.UniqueAddress
import akka.cluster.ddata.Replicator.Changed
import org.scalatest.{ Matchers, WordSpec }

import scala.collection.immutable.TreeMap

class EasySetSpec extends WordSpec with Matchers {

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

      //      val c8a = c5 merge c2aDelta merge c3aDelta merge c4aDelta

      val c7b = c5 merge c1b merge c2b merge c3b merge c4b

      println("c4bDelta before: " + c2bDelta.elementsMap.toString() + " " + c2bDelta._latestAppliedDeltas)

      val c8b = c5 merge c2bDelta merge c3bDelta merge c4bDelta

      println("c4bDelta after: " + c4bDelta.elementsMap.toString() + " " + c4bDelta._latestAppliedDeltas)

      // skipped deltas delete
      println("\n\n\n\n************************** MEGA DELTA MERGE")
      val deltaA = ORSet() merge c2aDelta merge c2bDelta merge c4bDelta merge c4aDelta
      println("foooooo")
      val deltaB = ORSet() merge c2aDelta merge c2bDelta merge c3aDelta merge c3bDelta merge c4aDelta merge c4bDelta
      println("\n\n\n********************** after delta merge done")

      val c9 = deltaA merge deltaB

      c9.elements should contain(user1)
      c9.elements should contain(user2)
      c9.elements should contain(user3)

    }

  }
}
