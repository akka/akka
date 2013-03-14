/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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

  val aRoles = Set("AA", "AB")
  val aJoining = Member(Address("akka.tcp", "sys", "a", 2552), Joining, aRoles)
  val aUp = Member(Address("akka.tcp", "sys", "a", 2552), Up, aRoles)
  val aRemoved = Member(Address("akka.tcp", "sys", "a", 2552), Removed, aRoles)
  val bRoles = Set("AB", "BB")
  val bUp = Member(Address("akka.tcp", "sys", "b", 2552), Up, bRoles)
  val bDown = Member(Address("akka.tcp", "sys", "b", 2552), Down, bRoles)
  val bRemoved = Member(Address("akka.tcp", "sys", "b", 2552), Removed, bRoles)
  val cRoles = Set.empty[String]
  val cUp = Member(Address("akka.tcp", "sys", "c", 2552), Up, cRoles)
  val cLeaving = Member(Address("akka.tcp", "sys", "c", 2552), Leaving, cRoles)
  val dRoles = Set("DD", "DE")
  val dLeaving = Member(Address("akka.tcp", "sys", "d", 2552), Leaving, dRoles)
  val dExiting = Member(Address("akka.tcp", "sys", "d", 2552), Exiting, dRoles)
  val dRemoved = Member(Address("akka.tcp", "sys", "d", 2552), Removed, dRoles)
  val eRoles = Set("EE", "DE")
  val eJoining = Member(Address("akka.tcp", "sys", "e", 2552), Joining, eRoles)
  val eUp = Member(Address("akka.tcp", "sys", "e", 2552), Up, eRoles)
  val eDown = Member(Address("akka.tcp", "sys", "e", 2552), Down, eRoles)

  def converge(gossip: Gossip): (Gossip, Set[Address]) =
    ((gossip, Set.empty[Address]) /: gossip.members) { (gs, m) ⇒ (gs._1.seen(m.address), gs._2 + m.address) }

  "Domain events" must {

    "be empty for the same gossip" in {
      val g1 = Gossip(members = SortedSet(aUp))

      diffUnreachable(g1, g1) must be(Seq.empty)
    }

    "be produced for new members" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aUp)))
      val (g2, s2) = converge(Gossip(members = SortedSet(aUp, bUp, eJoining)))

      diffMemberEvents(g1, g2) must be(Seq(MemberUp(bUp)))
      diffUnreachable(g1, g2) must be(Seq.empty)
      diffSeen(g1, g2) must be(Seq(SeenChanged(convergence = true, seenBy = s2)))
    }

    "be produced for changed status of members" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aJoining, bUp, cUp)))
      val (g2, s2) = converge(Gossip(members = SortedSet(aUp, bUp, cLeaving, eJoining)))

      diffMemberEvents(g1, g2) must be(Seq(MemberUp(aUp)))
      diffUnreachable(g1, g2) must be(Seq.empty)
      diffSeen(g1, g2) must be(Seq(SeenChanged(convergence = true, seenBy = s2)))
    }

    "be produced for members in unreachable" in {
      val g1 = Gossip(members = SortedSet(aUp, bUp), overview = GossipOverview(unreachable = Set(cUp, eUp)))
      val g2 = Gossip(members = SortedSet(aUp), overview = GossipOverview(unreachable = Set(cUp, bDown, eDown)))

      diffUnreachable(g1, g2) must be(Seq(UnreachableMember(bDown)))
      diffSeen(g1, g2) must be(Seq.empty)
    }

    "be produced for removed members" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aUp, dLeaving)))
      val (g2, s2) = converge(Gossip(members = SortedSet(aUp)))

      diffMemberEvents(g1, g2) must be(Seq(MemberRemoved(dRemoved)))
      diffUnreachable(g1, g2) must be(Seq.empty)
      diffSeen(g1, g2) must be(Seq(SeenChanged(convergence = true, seenBy = s2)))
    }

    "be produced for convergence changes" in {
      val g1 = Gossip(members = SortedSet(aUp, bUp, eJoining)).seen(aUp.address).seen(bUp.address).seen(eJoining.address)
      val g2 = Gossip(members = SortedSet(aUp, bUp, eJoining)).seen(aUp.address).seen(bUp.address)

      diffMemberEvents(g1, g2) must be(Seq.empty)
      diffUnreachable(g1, g2) must be(Seq.empty)
      diffSeen(g1, g2) must be(Seq(SeenChanged(convergence = false, seenBy = Set(aUp.address, bUp.address))))
      diffMemberEvents(g2, g1) must be(Seq.empty)
      diffUnreachable(g2, g1) must be(Seq.empty)
      diffSeen(g2, g1) must be(Seq(SeenChanged(convergence = true, seenBy = Set(aUp.address, bUp.address, eJoining.address))))
    }

    "be produced for leader changes" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aUp, bUp, eJoining)))
      val (g2, s2) = converge(Gossip(members = SortedSet(bUp, eJoining)))

      diffMemberEvents(g1, g2) must be(Seq(MemberRemoved(aRemoved)))
      diffUnreachable(g1, g2) must be(Seq.empty)
      diffSeen(g1, g2) must be(Seq(SeenChanged(convergence = true, seenBy = s2)))
      diffLeader(g1, g2) must be(Seq(LeaderChanged(Some(bUp.address))))
    }

    "be produced for role leader changes" in {
      val g0 = Gossip.empty
      val g1 = Gossip(members = SortedSet(aUp, bUp, cUp, dLeaving, eJoining))
      val g2 = Gossip(members = SortedSet(bUp, cUp, dExiting, eJoining))
      diffRolesLeader(g0, g1) must be(
        Set(RoleLeaderChanged("AA", Some(aUp.address)),
          RoleLeaderChanged("AB", Some(aUp.address)),
          RoleLeaderChanged("BB", Some(bUp.address)),
          RoleLeaderChanged("DD", Some(dLeaving.address)),
          RoleLeaderChanged("DE", Some(dLeaving.address)),
          RoleLeaderChanged("EE", Some(eUp.address))))
      diffRolesLeader(g1, g2) must be(
        Set(RoleLeaderChanged("AA", None),
          RoleLeaderChanged("AB", Some(bUp.address)),
          RoleLeaderChanged("DE", Some(eJoining.address))))
    }
  }
}
