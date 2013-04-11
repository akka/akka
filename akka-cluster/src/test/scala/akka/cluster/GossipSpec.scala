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
      merged1.members.toSeq.map(_.status) must be(Seq(Joining, Leaving, Joining))

      val merged2 = g2 merge g1
      merged2.members must be(SortedSet(a2, c1, e1))
      merged2.members.toSeq.map(_.status) must be(Seq(Joining, Leaving, Joining))

    }

    "merge unreachable by status priority" in {
      val g1 = Gossip(members = Gossip.emptyMembers, overview = GossipOverview(unreachable = Set(a1, b1, c1, d1)))
      val g2 = Gossip(members = Gossip.emptyMembers, overview = GossipOverview(unreachable = Set(a2, b2, c2, d2)))

      val merged1 = g1 merge g2
      merged1.overview.unreachable must be(Set(a2, b2, c1, d2))
      merged1.overview.unreachable.toSeq.sorted.map(_.status) must be(Seq(Joining, Removed, Leaving, Removed))

      val merged2 = g2 merge g1
      merged2.overview.unreachable must be(Set(a2, b2, c1, d2))
      merged2.overview.unreachable.toSeq.sorted.map(_.status) must be(Seq(Joining, Removed, Leaving, Removed))

    }

    "merge by excluding unreachable from members" in {
      val g1 = Gossip(members = SortedSet(a1, b1), overview = GossipOverview(unreachable = Set(c1, d1)))
      val g2 = Gossip(members = SortedSet(a2, c2), overview = GossipOverview(unreachable = Set(b2, d2)))

      val merged1 = g1 merge g2
      merged1.members must be(SortedSet(a2))
      merged1.members.toSeq.map(_.status) must be(Seq(Joining))
      merged1.overview.unreachable must be(Set(b2, c1, d2))
      merged1.overview.unreachable.toSeq.sorted.map(_.status) must be(Seq(Removed, Leaving, Removed))

      val merged2 = g2 merge g1
      merged2.members must be(SortedSet(a2))
      merged2.members.toSeq.map(_.status) must be(Seq(Joining))
      merged2.overview.unreachable must be(Set(b2, c1, d2))
      merged2.overview.unreachable.toSeq.sorted.map(_.status) must be(Seq(Removed, Leaving, Removed))

    }

    "not have node in both members and unreachable" in intercept[IllegalArgumentException] {
      Gossip(members = SortedSet(a1, b1), overview = GossipOverview(unreachable = Set(b2)))
    }

    "not have live members with wrong status" in intercept[IllegalArgumentException] {
      // b2 is Removed
      Gossip(members = SortedSet(a2, b2))
    }

    "not have non cluster members in seen table" in intercept[IllegalArgumentException] {
      Gossip(members = SortedSet(a1, e1)).seen(a1.uniqueAddress).seen(e1.uniqueAddress).seen(b1.uniqueAddress)
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
        val keys = merged.overview.seen.keys.toSeq
        keys.length must be(4)
        keys.toSet must be(Set(a1.uniqueAddress, b1.uniqueAddress, c1.uniqueAddress, d1.uniqueAddress))

        merged seenByNode (a1.uniqueAddress) must be(true)
        merged seenByNode (b1.uniqueAddress) must be(false)
        merged seenByNode (c1.uniqueAddress) must be(true)
        merged seenByNode (d1.uniqueAddress) must be(true)
        merged seenByNode (e1.uniqueAddress) must be(false)

        merged.overview.seen(b1.uniqueAddress) must be(g1.version)
      }

      checkMerged(g3 merge g2)
      checkMerged(g2 merge g3)
    }
  }
}
