/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import scala.collection.immutable.SortedSet

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterDomainEventSpec extends WordSpec with MustMatchers {

  import MemberStatus._
  import ClusterEvent._

  val a1 = Member(Address("akka", "sys", "a", 2552), Up)
  val a2 = Member(Address("akka", "sys", "a", 2552), Joining)
  val b1 = Member(Address("akka", "sys", "b", 2552), Up)
  val b2 = Member(Address("akka", "sys", "b", 2552), Removed)
  val b3 = Member(Address("akka", "sys", "b", 2552), Down)
  val c1 = Member(Address("akka", "sys", "c", 2552), Leaving)
  val c2 = Member(Address("akka", "sys", "c", 2552), Up)
  val d1 = Member(Address("akka", "sys", "d", 2552), Leaving)
  val d2 = Member(Address("akka", "sys", "d", 2552), Removed)
  val e1 = Member(Address("akka", "sys", "e", 2552), Joining)
  val e2 = Member(Address("akka", "sys", "e", 2552), Up)
  val e3 = Member(Address("akka", "sys", "e", 2552), Down)

  "Domain events" must {

    "be produced for new members" in {
      val g1 = Gossip(members = SortedSet(a1))
      val g2 = Gossip(members = SortedSet(a1, b1, e1))

      diff(g1, g2) must be(Seq(MemberUp(b1), MemberJoined(e1)))
    }

    "be produced for changed status of members" in {
      val g1 = Gossip(members = SortedSet(a2, b1, c2))
      val g2 = Gossip(members = SortedSet(a1, b1, c1, e1))

      diff(g1, g2) must be(Seq(MemberUp(a1), MemberLeft(c1), MemberJoined(e1)))
    }

    "be produced for unreachable members" in {
      val g1 = Gossip(members = SortedSet(a1, b1), overview = GossipOverview(unreachable = Set(c2)))
      val g2 = Gossip(members = SortedSet(a1), overview = GossipOverview(unreachable = Set(b1, c2)))

      diff(g1, g2) must be(Seq(MemberUnreachable(b1)))
    }

    "be produced for downed members" in {
      val g1 = Gossip(members = SortedSet(a1, b1), overview = GossipOverview(unreachable = Set(c2, e2)))
      val g2 = Gossip(members = SortedSet(a1), overview = GossipOverview(unreachable = Set(c2, b3, e3)))

      diff(g1, g2) must be(Seq(MemberDowned(b3), MemberDowned(e3)))
    }

    "be produced for removed members" in {
      val g1 = Gossip(members = SortedSet(a1, d1), overview = GossipOverview(unreachable = Set(c2)))
      val g2 = Gossip(members = SortedSet(a1), overview = GossipOverview(unreachable = Set(c2)))

      diff(g1, g2) must be(Seq(MemberRemoved(d2)))
    }

    "be produced for convergence changes" in {
      val g1 = Gossip(members = SortedSet(a1, b1, e1)).seen(a1.address).seen(b1.address).seen(e1.address)
      val g2 = Gossip(members = SortedSet(a1, b1, e1)).seen(a1.address).seen(b1.address)

      // LeaderChanged is also published when convergence changed
      diff(g1, g2) must be(Seq(ConvergenceChanged(false), LeaderChanged(Some(a1.address), convergence = false),
        SeenChanged(convergence = false, seenBy = Set(a1.address, b1.address))))
      diff(g2, g1) must be(Seq(ConvergenceChanged(true), LeaderChanged(Some(a1.address), convergence = true),
        SeenChanged(convergence = true, seenBy = Set(a1.address, b1.address, e1.address))))
    }

    "be produced for leader changes" in {
      val g1 = Gossip(members = SortedSet(a1, b1, e1))
      val g2 = Gossip(members = SortedSet(b1, e1), overview = GossipOverview(unreachable = Set(a1)))
      val g3 = g2.copy(overview = GossipOverview()).seen(b1.address).seen(e1.address)

      diff(g1, g2) must be(Seq(MemberUnreachable(a1), LeaderChanged(Some(b1.address), convergence = false)))
      diff(g2, g3) must be(Seq(ConvergenceChanged(true), LeaderChanged(Some(b1.address), convergence = true),
        SeenChanged(convergence = true, seenBy = Set(b1.address, e1.address))))
    }
  }
}
