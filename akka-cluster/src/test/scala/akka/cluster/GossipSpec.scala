/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import scala.collection.immutable.SortedSet

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class GossipSpec extends WordSpec with MustMatchers {

  import MemberStatus._

  val a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), Up)
  val a2 = TestMember(a1.address, Joining)
  val b1 = TestMember(Address("akka.tcp", "sys", "b", 2552), Up)
  val b2 = TestMember(b1.address, Removed)
  val c1 = TestMember(Address("akka.tcp", "sys", "c", 2552), Leaving)
  val c2 = TestMember(c1.address, Up)
  val c3 = TestMember(c1.address, Exiting)
  val d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), Leaving)
  val d2 = TestMember(d1.address, Removed)
  val e1 = TestMember(Address("akka.tcp", "sys", "e", 2552), Joining)
  val e2 = TestMember(e1.address, Up)
  val e3 = TestMember(e1.address, Down)

  "A Gossip" must {

    "reach convergence when it's empty" in {
      Gossip.empty.convergence must be(true)
    }

    "merge members by status priority" in {
      val g1 = Gossip(members = SortedSet(a1, c1, e1))
      val g2 = Gossip(members = SortedSet(a2, c2, e2))

      val merged1 = g1 merge g2
      merged1.members must be(SortedSet(a2, c1, e1))
      merged1.members.toSeq.map(_.status) must be(Seq(Up, Leaving, Up))

      val merged2 = g2 merge g1
      merged2.members must be(SortedSet(a2, c1, e1))
      merged2.members.toSeq.map(_.status) must be(Seq(Up, Leaving, Up))

    }

    "merge unreachable" in {
      val r1 = Reachability.empty.unreachable(b1.uniqueAddress, a1.uniqueAddress).unreachable(b1.uniqueAddress, c1.uniqueAddress)
      val g1 = Gossip(members = SortedSet(a1, b1, c1), overview = GossipOverview(reachability = r1))
      val r2 = Reachability.empty.unreachable(a1.uniqueAddress, d1.uniqueAddress)
      val g2 = Gossip(members = SortedSet(a1, b1, c1, d1), overview = GossipOverview(reachability = r2))

      val merged1 = g1 merge g2
      merged1.overview.reachability.allUnreachable must be(Set(a1.uniqueAddress, c1.uniqueAddress, d1.uniqueAddress))

      val merged2 = g2 merge g1
      merged2.overview.reachability.allUnreachable must be(merged1.overview.reachability.allUnreachable)
    }

    "have leader as first member based on ordering, except Exiting status" in {
      Gossip(members = SortedSet(c2, e2)).leader must be(Some(c2.uniqueAddress))
      Gossip(members = SortedSet(c3, e2)).leader must be(Some(e2.uniqueAddress))
      Gossip(members = SortedSet(c3)).leader must be(Some(c3.uniqueAddress))
    }

    "merge seen table correctly" in {
      val vclockNode = VectorClock.Node("something")
      val g1 = (Gossip(members = SortedSet(a1, b1, c1, d1)) :+ vclockNode).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      val g2 = (Gossip(members = SortedSet(a1, b1, c1, d1)) :+ vclockNode).seen(a1.uniqueAddress).seen(c1.uniqueAddress)
      val g3 = (g1 copy (version = g2.version)).seen(d1.uniqueAddress)

      def checkMerged(merged: Gossip) {
        val seen = merged.overview.seen.toSeq
        seen.length must be(0)

        merged seenByNode (a1.uniqueAddress) must be(false)
        merged seenByNode (b1.uniqueAddress) must be(false)
        merged seenByNode (c1.uniqueAddress) must be(false)
        merged seenByNode (d1.uniqueAddress) must be(false)
        merged seenByNode (e1.uniqueAddress) must be(false)
      }

      checkMerged(g3 merge g2)
      checkMerged(g2 merge g3)
    }

    "know who is youngest" in {
      // a2 and e1 is Joining
      val g1 = Gossip(members = SortedSet(a2, b1.copyUp(3), e1), overview = GossipOverview(reachability =
        Reachability.empty.unreachable(a2.uniqueAddress, e1.uniqueAddress)))
      g1.youngestMember must be(b1)
      val g2 = Gossip(members = SortedSet(a2, b1.copyUp(3), e1), overview = GossipOverview(reachability =
        Reachability.empty.unreachable(a2.uniqueAddress, b1.uniqueAddress).unreachable(a2.uniqueAddress, e1.uniqueAddress)))
      g2.youngestMember must be(b1)
      val g3 = Gossip(members = SortedSet(a2, b1.copyUp(3), e2.copyUp(4)))
      g3.youngestMember must be(e2)
    }
  }
}
