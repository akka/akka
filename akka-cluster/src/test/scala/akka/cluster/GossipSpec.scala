/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address
import scala.collection.immutable.SortedSet

class GossipSpec extends WordSpec with Matchers {

  import MemberStatus._

  val a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), Up)
  val a2 = TestMember(a1.address, Joining)
  val b1 = TestMember(Address("akka.tcp", "sys", "b", 2552), Up)
  val b2 = TestMember(b1.address, Removed)
  val c1 = TestMember(Address("akka.tcp", "sys", "c", 2552), Leaving)
  val c2 = TestMember(c1.address, Up)
  val c3 = TestMember(c1.address, Exiting)
  val d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), Leaving)
  val e1 = TestMember(Address("akka.tcp", "sys", "e", 2552), Joining)
  val e2 = TestMember(e1.address, Up)
  val e3 = TestMember(e1.address, Down)

  "A Gossip" must {

    "reach convergence when it's empty" in {
      Gossip.empty.convergence(a1.uniqueAddress) should ===(true)
    }

    "reach convergence for one node" in {
      val g1 = (Gossip(members = SortedSet(a1))).seen(a1.uniqueAddress)
      g1.convergence(a1.uniqueAddress) should ===(true)
    }

    "not reach convergence until all have seen version" in {
      val g1 = (Gossip(members = SortedSet(a1, b1))).seen(a1.uniqueAddress)
      g1.convergence(a1.uniqueAddress) should ===(false)
    }

    "reach convergence for two nodes" in {
      val g1 = (Gossip(members = SortedSet(a1, b1))).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(a1.uniqueAddress) should ===(true)
    }

    "reach convergence, skipping joining" in {
      // e1 is joining
      val g1 = (Gossip(members = SortedSet(a1, b1, e1))).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(a1.uniqueAddress) should ===(true)
    }

    "reach convergence, skipping down" in {
      // e3 is down
      val g1 = (Gossip(members = SortedSet(a1, b1, e3))).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(a1.uniqueAddress) should ===(true)
    }

    "not reach convergence when unreachable" in {
      val r1 = Reachability.empty.unreachable(b1.uniqueAddress, a1.uniqueAddress)
      val g1 = (Gossip(members = SortedSet(a1, b1), overview = GossipOverview(reachability = r1)))
        .seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(b1.uniqueAddress) should ===(false)
      // but from a1's point of view (it knows that itself is not unreachable)
      g1.convergence(a1.uniqueAddress) should ===(true)
    }

    "reach convergence when downed node has observed unreachable" in {
      // e3 is Down
      val r1 = Reachability.empty.unreachable(e3.uniqueAddress, a1.uniqueAddress)
      val g1 = (Gossip(members = SortedSet(a1, b1, e3), overview = GossipOverview(reachability = r1)))
        .seen(a1.uniqueAddress).seen(b1.uniqueAddress).seen(e3.uniqueAddress)
      g1.convergence(b1.uniqueAddress) should ===(true)
    }

    "merge members by status priority" in {
      val g1 = Gossip(members = SortedSet(a1, c1, e1))
      val g2 = Gossip(members = SortedSet(a2, c2, e2))

      val merged1 = g1 merge g2
      merged1.members should ===(SortedSet(a2, c1, e1))
      merged1.members.toSeq.map(_.status) should ===(Seq(Up, Leaving, Up))

      val merged2 = g2 merge g1
      merged2.members should ===(SortedSet(a2, c1, e1))
      merged2.members.toSeq.map(_.status) should ===(Seq(Up, Leaving, Up))

    }

    "merge unreachable" in {
      val r1 = Reachability.empty.unreachable(b1.uniqueAddress, a1.uniqueAddress).unreachable(b1.uniqueAddress, c1.uniqueAddress)
      val g1 = Gossip(members = SortedSet(a1, b1, c1), overview = GossipOverview(reachability = r1))
      val r2 = Reachability.empty.unreachable(a1.uniqueAddress, d1.uniqueAddress)
      val g2 = Gossip(members = SortedSet(a1, b1, c1, d1), overview = GossipOverview(reachability = r2))

      val merged1 = g1 merge g2
      merged1.overview.reachability.allUnreachable should ===(Set(a1.uniqueAddress, c1.uniqueAddress, d1.uniqueAddress))

      val merged2 = g2 merge g1
      merged2.overview.reachability.allUnreachable should ===(merged1.overview.reachability.allUnreachable)
    }

    "merge members by removing removed members" in {
      // c3 removed
      val r1 = Reachability.empty.unreachable(b1.uniqueAddress, a1.uniqueAddress)
      val g1 = Gossip(members = SortedSet(a1, b1), overview = GossipOverview(reachability = r1))
      val r2 = r1.unreachable(b1.uniqueAddress, c3.uniqueAddress)
      val g2 = Gossip(members = SortedSet(a1, b1, c3), overview = GossipOverview(reachability = r2))

      val merged1 = g1 merge g2
      merged1.members should ===(SortedSet(a1, b1))
      merged1.overview.reachability.allUnreachable should ===(Set(a1.uniqueAddress))

      val merged2 = g2 merge g1
      merged2.overview.reachability.allUnreachable should ===(merged1.overview.reachability.allUnreachable)
      merged2.members should ===(merged1.members)
    }

    "have leader as first member based on ordering, except Exiting status" in {
      Gossip(members = SortedSet(c2, e2)).leader(c2.uniqueAddress) should ===(Some(c2.uniqueAddress))
      Gossip(members = SortedSet(c3, e2)).leader(c3.uniqueAddress) should ===(Some(e2.uniqueAddress))
      Gossip(members = SortedSet(c3)).leader(c3.uniqueAddress) should ===(Some(c3.uniqueAddress))
    }

    "have leader as first reachable member based on ordering" in {
      val r1 = Reachability.empty.unreachable(e2.uniqueAddress, c2.uniqueAddress)
      val g1 = Gossip(members = SortedSet(c2, e2), overview = GossipOverview(reachability = r1))
      g1.leader(e2.uniqueAddress) should ===(Some(e2.uniqueAddress))
      // but when c2 is selfUniqueAddress
      g1.leader(c2.uniqueAddress) should ===(Some(c2.uniqueAddress))
    }

    "not have Down member as leader" in {
      Gossip(members = SortedSet(e3)).leader(e3.uniqueAddress) should ===(None)
    }

    "merge seen table correctly" in {
      val vclockNode = VectorClock.Node("something")
      val g1 = (Gossip(members = SortedSet(a1, b1, c1, d1)) :+ vclockNode).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      val g2 = (Gossip(members = SortedSet(a1, b1, c1, d1)) :+ vclockNode).seen(a1.uniqueAddress).seen(c1.uniqueAddress)
      val g3 = (g1 copy (version = g2.version)).seen(d1.uniqueAddress)

      def checkMerged(merged: Gossip) {
        val seen = merged.overview.seen.toSeq
        seen.length should ===(0)

        merged seenByNode (a1.uniqueAddress) should ===(false)
        merged seenByNode (b1.uniqueAddress) should ===(false)
        merged seenByNode (c1.uniqueAddress) should ===(false)
        merged seenByNode (d1.uniqueAddress) should ===(false)
        merged seenByNode (e1.uniqueAddress) should ===(false)
      }

      checkMerged(g3 merge g2)
      checkMerged(g2 merge g3)
    }

    "know who is youngest" in {
      // a2 and e1 is Joining
      val g1 = Gossip(members = SortedSet(a2, b1.copyUp(3), e1), overview = GossipOverview(reachability =
        Reachability.empty.unreachable(a2.uniqueAddress, e1.uniqueAddress)))
      g1.youngestMember should ===(b1)
      val g2 = Gossip(members = SortedSet(a2, b1.copyUp(3), e1), overview = GossipOverview(reachability =
        Reachability.empty.unreachable(a2.uniqueAddress, b1.uniqueAddress).unreachable(a2.uniqueAddress, e1.uniqueAddress)))
      g2.youngestMember should ===(b1)
      val g3 = Gossip(members = SortedSet(a2, b1.copyUp(3), e2.copyUp(4)))
      g3.youngestMember should ===(e2)
    }
  }
}
