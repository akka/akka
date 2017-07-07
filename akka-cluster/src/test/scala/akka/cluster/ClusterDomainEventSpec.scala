/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address

import scala.collection.immutable.SortedSet

class ClusterDomainEventSpec extends WordSpec with Matchers {

  import MemberStatus._
  import ClusterEvent._

  val aRoles = Set("AA", "AB")
  val aJoining = TestMember(Address("akka.tcp", "sys", "a", 2552), Joining, aRoles)
  val aUp = TestMember(Address("akka.tcp", "sys", "a", 2552), Up, aRoles)
  val aRemoved = TestMember(Address("akka.tcp", "sys", "a", 2552), Removed, aRoles)
  val bRoles = Set("AB", "BB")
  val bUp = TestMember(Address("akka.tcp", "sys", "b", 2552), Up, bRoles)
  val bDown = TestMember(Address("akka.tcp", "sys", "b", 2552), Down, bRoles)
  val bRemoved = TestMember(Address("akka.tcp", "sys", "b", 2552), Removed, bRoles)
  val cRoles = Set.empty[String]
  val cUp = TestMember(Address("akka.tcp", "sys", "c", 2552), Up, cRoles)
  val cLeaving = TestMember(Address("akka.tcp", "sys", "c", 2552), Leaving, cRoles)
  val dRoles = Set("DD", "DE")
  val dLeaving = TestMember(Address("akka.tcp", "sys", "d", 2552), Leaving, dRoles)
  val dExiting = TestMember(Address("akka.tcp", "sys", "d", 2552), Exiting, dRoles)
  val dRemoved = TestMember(Address("akka.tcp", "sys", "d", 2552), Removed, dRoles)
  val eRoles = Set("EE", "DE")
  val eJoining = TestMember(Address("akka.tcp", "sys", "e", 2552), Joining, eRoles)
  val eUp = TestMember(Address("akka.tcp", "sys", "e", 2552), Up, eRoles)
  val eDown = TestMember(Address("akka.tcp", "sys", "e", 2552), Down, eRoles)
  val selfDummyAddress = UniqueAddress(Address("akka.tcp", "sys", "selfDummy", 2552), 17L)

  private[cluster] def converge(gossip: Gossip): (Gossip, Set[UniqueAddress]) =
    ((gossip, Set.empty[UniqueAddress]) /: gossip.members) { case ((gs, as), m) ⇒ (gs.seen(m.uniqueAddress), as + m.uniqueAddress) }

  private def state(g: Gossip): MembershipState =
    state(g, selfDummyAddress)

  private def state(g: Gossip, self: UniqueAddress): MembershipState =
    MembershipState(g, self, ClusterSettings.DefaultDataCenter, crossDcConnections = 5)

  "Domain events" must {

    "be empty for the same gossip" in {
      val g1 = Gossip(members = SortedSet(aUp))

      diffUnreachable(state(g1), state(g1)) should ===(Seq.empty)
    }

    "be produced for new members" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aUp)))
      val (g2, s2) = converge(Gossip(members = SortedSet(aUp, bUp, eJoining)))

      diffMemberEvents(state(g1), state(g2)) should ===(Seq(MemberUp(bUp), MemberJoined(eJoining)))
      diffUnreachable(state(g1), state(g2)) should ===(Seq.empty)
      diffSeen(state(g1), state(g2)) should ===(Seq(SeenChanged(convergence = true, seenBy = s2.map(_.address))))
    }

    "be produced for changed status of members" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aJoining, bUp, cUp)))
      val (g2, s2) = converge(Gossip(members = SortedSet(aUp, bUp, cLeaving, eJoining)))

      diffMemberEvents(state(g1), state(g2)) should ===(Seq(MemberUp(aUp), MemberLeft(cLeaving), MemberJoined(eJoining)))
      diffUnreachable(state(g1), state(g2)) should ===(Seq.empty)
      diffSeen(state(g1), state(g2)) should ===(Seq(SeenChanged(convergence = true, seenBy = s2.map(_.address))))
    }

    "be produced for members in unreachable" in {
      val reachability1 = Reachability.empty.
        unreachable(aUp.uniqueAddress, cUp.uniqueAddress).
        unreachable(aUp.uniqueAddress, eUp.uniqueAddress)
      val g1 = Gossip(members = SortedSet(aUp, bUp, cUp, eUp), overview = GossipOverview(reachability = reachability1))
      val reachability2 = reachability1.
        unreachable(aUp.uniqueAddress, bDown.uniqueAddress)
      val g2 = Gossip(members = SortedSet(aUp, cUp, bDown, eDown), overview = GossipOverview(reachability = reachability2))

      diffUnreachable(state(g1), state(g2)) should ===(Seq(UnreachableMember(bDown)))
      // never include self member in unreachable

      diffUnreachable(
        state(g1, bDown.uniqueAddress),
        state(g2, bDown.uniqueAddress)) should ===(Seq())
      diffSeen(state(g1), state(g2)) should ===(Seq.empty)
    }

    "be produced for members becoming reachable after unreachable" in {
      val reachability1 = Reachability.empty.
        unreachable(aUp.uniqueAddress, cUp.uniqueAddress).reachable(aUp.uniqueAddress, cUp.uniqueAddress).
        unreachable(aUp.uniqueAddress, eUp.uniqueAddress).
        unreachable(aUp.uniqueAddress, bUp.uniqueAddress)
      val g1 = Gossip(members = SortedSet(aUp, bUp, cUp, eUp), overview = GossipOverview(reachability = reachability1))
      val reachability2 = reachability1.
        unreachable(aUp.uniqueAddress, cUp.uniqueAddress).
        reachable(aUp.uniqueAddress, bUp.uniqueAddress)
      val g2 = Gossip(members = SortedSet(aUp, cUp, bUp, eUp), overview = GossipOverview(reachability = reachability2))

      diffUnreachable(state(g1), state(g2)) should ===(Seq(UnreachableMember(cUp)))
      // never include self member in unreachable
      diffUnreachable(
        state(g1, cUp.uniqueAddress),
        state(g2, cUp.uniqueAddress)) should ===(Seq())
      diffReachable(state(g1), state(g2)) should ===(Seq(ReachableMember(bUp)))
      // never include self member in reachable
      diffReachable(
        state(g1, bUp.uniqueAddress),
        state(g2, bUp.uniqueAddress)) should ===(Seq())
    }

    "be produced for removed members" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aUp, dExiting)))
      val (g2, s2) = converge(Gossip(members = SortedSet(aUp)))

      diffMemberEvents(state(g1), state(g2)) should ===(Seq(MemberRemoved(dRemoved, Exiting)))
      diffUnreachable(state(g1), state(g2)) should ===(Seq.empty)
      diffSeen(state(g1), state(g2)) should ===(Seq(SeenChanged(convergence = true, seenBy = s2.map(_.address))))
    }

    "be produced for convergence changes" in {
      val g1 = Gossip(members = SortedSet(aUp, bUp, eJoining)).seen(aUp.uniqueAddress).seen(bUp.uniqueAddress).seen(eJoining.uniqueAddress)
      val g2 = Gossip(members = SortedSet(aUp, bUp, eJoining)).seen(aUp.uniqueAddress).seen(bUp.uniqueAddress)

      diffMemberEvents(state(g1), state(g2)) should ===(Seq.empty)
      diffUnreachable(state(g1), state(g2)) should ===(Seq.empty)
      diffSeen(state(g1), state(g2)) should ===(Seq(SeenChanged(convergence = true, seenBy = Set(aUp.address, bUp.address))))
      diffMemberEvents(state(g2), state(g1)) should ===(Seq.empty)
      diffUnreachable(state(g2), state(g1)) should ===(Seq.empty)
      diffSeen(state(g2), state(g1)) should ===(Seq(SeenChanged(convergence = true, seenBy = Set(aUp.address, bUp.address, eJoining.address))))
    }

    "be produced for leader changes" in {
      val (g1, _) = converge(Gossip(members = SortedSet(aUp, bUp, eJoining)))
      val (g2, s2) = converge(Gossip(members = SortedSet(bUp, eJoining)))

      diffMemberEvents(state(g1), state(g2)) should ===(Seq(MemberRemoved(aRemoved, Up)))
      diffUnreachable(state(g1), state(g2)) should ===(Seq.empty)
      diffSeen(state(g1), state(g2)) should ===(Seq(SeenChanged(convergence = true, seenBy = s2.map(_.address))))
      diffLeader(state(g1), state(g2)) should ===(Seq(LeaderChanged(Some(bUp.address))))
    }

    "be produced for role leader changes in the same data center" in {
      val g0 = Gossip.empty
      val g1 = Gossip(members = SortedSet(aUp, bUp, cUp, dLeaving, eJoining))
      val g2 = Gossip(members = SortedSet(bUp, cUp, dExiting, eJoining))
      diffRolesLeader(state(g0), state(g1)) should ===(
        Set(
          // since this role is implicitly added
          RoleLeaderChanged(ClusterSettings.DcRolePrefix + ClusterSettings.DefaultDataCenter, Some(aUp.address)),
          RoleLeaderChanged("AA", Some(aUp.address)),
          RoleLeaderChanged("AB", Some(aUp.address)),
          RoleLeaderChanged("BB", Some(bUp.address)),
          RoleLeaderChanged("DD", Some(dLeaving.address)),
          RoleLeaderChanged("DE", Some(dLeaving.address)),
          RoleLeaderChanged("EE", Some(eUp.address))))
      diffRolesLeader(state(g1), state(g2)) should ===(
        Set(
          RoleLeaderChanged(ClusterSettings.DcRolePrefix + ClusterSettings.DefaultDataCenter, Some(bUp.address)),
          RoleLeaderChanged("AA", None),
          RoleLeaderChanged("AB", Some(bUp.address)),
          RoleLeaderChanged("DE", Some(eJoining.address))))
    }

    "not be produced for role leader changes in other data centers" in {
      val g0 = Gossip.empty
      val s0 = state(g0).copy(selfDc = "dc2")
      val g1 = Gossip(members = SortedSet(aUp, bUp, cUp, dLeaving, eJoining))
      val s1 = state(g1).copy(selfDc = "dc2")
      val g2 = Gossip(members = SortedSet(bUp, cUp, dExiting, eJoining))
      val s2 = state(g2).copy(selfDc = "dc2")

      diffRolesLeader(s0, s1) should ===(Set.empty)
      diffRolesLeader(s1, s2) should ===(Set.empty)
    }
  }
}
