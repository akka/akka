/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Address
import akka.cluster.MemberStatus.Up
import org.scalatest.{ Matchers, WordSpec }

import scala.collection.immutable.SortedSet

class MembershipStateSpec extends WordSpec with Matchers {
  // DC-a is in reverse age order
  val a1 = TestMember(Address("akka.tcp", "sys", "a4", 2552), Up, 1, "dc-a")
  val a2 = TestMember(Address("akka.tcp", "sys", "a3", 2552), Up, 2, "dc-a")
  val a3 = TestMember(Address("akka.tcp", "sys", "a2", 2552), Up, 3, "dc-a")
  val a4 = TestMember(Address("akka.tcp", "sys", "a1", 2552), Up, 4, "dc-a")

  // DC-b it is the first and the last that are the oldest
  val b1 = TestMember(Address("akka.tcp", "sys", "b3", 2552), Up, 1, "dc-b")
  val b3 = TestMember(Address("akka.tcp", "sys", "b2", 2552), Up, 3, "dc-b")
  // Won't be replaced by b3
  val b2 = TestMember(Address("akka.tcp", "sys", "b1", 2552), Up, 2, "dc-b")
  // for the case that we don't replace it ever
  val bOldest = TestMember(Address("akka.tcp", "sys", "b0", 2552), Up, 0, "dc-b")

  "Membership state" must {
    "sort by upNumber for oldest top members" in {
      val gossip = Gossip(SortedSet(a1, a2, a3, a4, b1, b2, b3, bOldest))
      val membershipState = MembershipState(
        gossip,
        a1.uniqueAddress,
        "dc-a",
        2
      )

      membershipState.ageSortedTopOldestMembersPerDc should equal(Map(
        "dc-a" -> SortedSet(a1, a2),
        "dc-b" -> SortedSet(bOldest, b1)
      ))
    }
  }
}
