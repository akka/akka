/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import scala.collection.immutable.SortedSet

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class GossipSpec extends WordSpec with MustMatchers {

  import MemberStatus._

  val a1 = Member(Address("akka", "sys", "a", 2552), Up)
  val a2 = Member(Address("akka", "sys", "a", 2552), Joining)
  val b1 = Member(Address("akka", "sys", "b", 2552), Up)
  val b2 = Member(Address("akka", "sys", "b", 2552), Removed)
  val c1 = Member(Address("akka", "sys", "c", 2552), Leaving)
  val c2 = Member(Address("akka", "sys", "c", 2552), Up)
  val d1 = Member(Address("akka", "sys", "d", 2552), Leaving)
  val d2 = Member(Address("akka", "sys", "d", 2552), Removed)
  val e1 = Member(Address("akka", "sys", "e", 2552), Joining)
  val e2 = Member(Address("akka", "sys", "e", 2552), Up)

  "A Gossip" must {

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

    "start with fresh seen table after merge" in {
      val g1 = Gossip(members = SortedSet(a1, e1)).seen(a1.address).seen(a1.address)
      val g2 = Gossip(members = SortedSet(a2, e2)).seen(e2.address).seen(e2.address)

      val merged1 = g1 merge g2
      merged1.overview.seen.isEmpty must be(true)

      val merged2 = g2 merge g1
      merged2.overview.seen.isEmpty must be(true)

    }

    "not have node in both members and unreachable" in intercept[IllegalArgumentException] {
      Gossip(members = SortedSet(a1, b1), overview = GossipOverview(unreachable = Set(b2)))
    }

    "not have live members with wrong status" in intercept[IllegalArgumentException] {
      // b2 is Removed
      Gossip(members = SortedSet(a2, b2))
    }

    "not have non cluster members in seen table" in intercept[IllegalArgumentException] {
      Gossip(members = SortedSet(a1, e1)).seen(a1.address).seen(e1.address).seen(b1.address)
    }

  }
}
