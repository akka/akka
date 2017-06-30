/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address
import akka.cluster.ClusterSettings.DefaultTeam

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

  val dc1a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), Up, Set.empty, team = "dc1")
  val dc1b1 = TestMember(Address("akka.tcp", "sys", "b", 2552), Up, Set.empty, team = "dc1")
  val dc2c1 = TestMember(Address("akka.tcp", "sys", "c", 2552), Up, Set.empty, team = "dc2")
  val dc2d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), Up, Set.empty, team = "dc2")
  val dc2d2 = TestMember(dc2d1.address, status = Down, roles = Set.empty, team = dc2d1.team)

  "A Gossip" must {

    "have correct test setup" in {
      List(a1, a2, b1, b2, c1, c2, c3, d1, e1, e2, e3).foreach(m ⇒
        m.team should ===(DefaultTeam)
      )
    }

    "reach convergence when it's empty" in {
      Gossip.empty.convergence(DefaultTeam, a1.uniqueAddress, Set.empty) should ===(true)
    }

    "reach convergence for one node" in {
      val g1 = Gossip(members = SortedSet(a1)).seen(a1.uniqueAddress)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set.empty) should ===(true)
    }

    "not reach convergence until all have seen version" in {
      val g1 = Gossip(members = SortedSet(a1, b1)).seen(a1.uniqueAddress)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set.empty) should ===(false)
    }

    "reach convergence for two nodes" in {
      val g1 = Gossip(members = SortedSet(a1, b1)).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set.empty) should ===(true)
    }

    "reach convergence, skipping joining" in {
      // e1 is joining
      val g1 = Gossip(members = SortedSet(a1, b1, e1)).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set.empty) should ===(true)
    }

    "reach convergence, skipping down" in {
      // e3 is down
      val g1 = Gossip(members = SortedSet(a1, b1, e3)).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set.empty) should ===(true)
    }

    "reach convergence, skipping Leaving with exitingConfirmed" in {
      // c1 is Leaving
      val g1 = Gossip(members = SortedSet(a1, b1, c1)).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set(c1.uniqueAddress)) should ===(true)
    }

    "reach convergence, skipping unreachable Leaving with exitingConfirmed" in {
      // c1 is Leaving
      val r1 = Reachability.empty.unreachable(b1.uniqueAddress, c1.uniqueAddress)
      val g1 = Gossip(members = SortedSet(a1, b1, c1), overview = GossipOverview(reachability = r1))
        .seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set(c1.uniqueAddress)) should ===(true)
    }

    "not reach convergence when unreachable" in {
      val r1 = Reachability.empty.unreachable(b1.uniqueAddress, a1.uniqueAddress)
      val g1 = (Gossip(members = SortedSet(a1, b1), overview = GossipOverview(reachability = r1)))
        .seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      g1.convergence(DefaultTeam, b1.uniqueAddress, Set.empty) should ===(false)
      // but from a1's point of view (it knows that itself is not unreachable)
      g1.convergence(DefaultTeam, a1.uniqueAddress, Set.empty) should ===(true)
    }

    "reach convergence when downed node has observed unreachable" in {
      // e3 is Down
      val r1 = Reachability.empty.unreachable(e3.uniqueAddress, a1.uniqueAddress)
      val g1 = (Gossip(members = SortedSet(a1, b1, e3), overview = GossipOverview(reachability = r1)))
        .seen(a1.uniqueAddress).seen(b1.uniqueAddress).seen(e3.uniqueAddress)
      g1.convergence(DefaultTeam, b1.uniqueAddress, Set.empty) should ===(true)
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
      Gossip(members = SortedSet(c2, e2)).teamLeader(DefaultTeam, c2.uniqueAddress) should ===(Some(c2.uniqueAddress))
      Gossip(members = SortedSet(c3, e2)).teamLeader(DefaultTeam, c3.uniqueAddress) should ===(Some(e2.uniqueAddress))
      Gossip(members = SortedSet(c3)).teamLeader(DefaultTeam, c3.uniqueAddress) should ===(Some(c3.uniqueAddress))
    }

    "have leader as first reachable member based on ordering" in {
      val r1 = Reachability.empty.unreachable(e2.uniqueAddress, c2.uniqueAddress)
      val g1 = Gossip(members = SortedSet(c2, e2), overview = GossipOverview(reachability = r1))
      g1.teamLeader(DefaultTeam, e2.uniqueAddress) should ===(Some(e2.uniqueAddress))
      // but when c2 is selfUniqueAddress
      g1.teamLeader(DefaultTeam, c2.uniqueAddress) should ===(Some(c2.uniqueAddress))
    }

    "not have Down member as leader" in {
      Gossip(members = SortedSet(e3)).teamLeader(DefaultTeam, e3.uniqueAddress) should ===(None)
    }

    "have a leader per team" in {
      val g1 = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1))

      // everybodys point of view is dc1a1 being leader of dc1
      g1.teamLeader("dc1", dc1a1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g1.teamLeader("dc1", dc1b1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g1.teamLeader("dc1", dc2c1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g1.teamLeader("dc1", dc2d1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))

      // and dc2c1 being leader of dc2
      g1.teamLeader("dc2", dc1a1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g1.teamLeader("dc2", dc1b1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g1.teamLeader("dc2", dc2c1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g1.teamLeader("dc2", dc2d1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
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

    "reach convergence per team" in {
      val g = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1))
        .seen(dc1a1.uniqueAddress)
        .seen(dc1b1.uniqueAddress)
        .seen(dc2c1.uniqueAddress)
        .seen(dc2d1.uniqueAddress)
      g.teamLeader("dc1", dc1a1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g.convergence("dc1", dc1a1.uniqueAddress, Set.empty) should ===(true)

      g.teamLeader("dc2", dc2c1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g.convergence("dc2", dc2c1.uniqueAddress, Set.empty) should ===(true)
    }

    "reach convergence per team even if members of another team has not seen the gossip" in {
      val g = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1))
        .seen(dc1a1.uniqueAddress)
        .seen(dc1b1.uniqueAddress)
        .seen(dc2c1.uniqueAddress)
      // dc2d1 has not seen the gossip

      // so dc1 can reach convergence
      g.teamLeader("dc1", dc1a1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g.convergence("dc1", dc1a1.uniqueAddress, Set.empty) should ===(true)

      // but dc2 cannot
      g.teamLeader("dc2", dc2c1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g.convergence("dc2", dc2c1.uniqueAddress, Set.empty) should ===(false)
    }

    "reach convergence per team even if another team contains unreachable" in {
      val r1 = Reachability.empty.unreachable(dc2c1.uniqueAddress, dc2d1.uniqueAddress)

      val g = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1), overview = GossipOverview(reachability = r1))
        .seen(dc1a1.uniqueAddress)
        .seen(dc1b1.uniqueAddress)
        .seen(dc2c1.uniqueAddress)
        .seen(dc2d1.uniqueAddress)

      // this team doesn't care about dc2 having reachability problems and can reach convergence
      g.teamLeader("dc1", dc1a1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g.convergence("dc1", dc1a1.uniqueAddress, Set.empty) should ===(true)

      // this team is cannot reach convergence because of unreachability within the team
      g.teamLeader("dc2", dc2c1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g.convergence("dc2", dc2c1.uniqueAddress, Set.empty) should ===(false)
    }

    "reach convergence per team even if there is unreachable nodes in another team" in {
      val r1 = Reachability.empty
        .unreachable(dc1a1.uniqueAddress, dc2d1.uniqueAddress)
        .unreachable(dc2d1.uniqueAddress, dc1a1.uniqueAddress)

      val g = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1), overview = GossipOverview(reachability = r1))
        .seen(dc1a1.uniqueAddress)
        .seen(dc1b1.uniqueAddress)
        .seen(dc2c1.uniqueAddress)
        .seen(dc2d1.uniqueAddress)

      // neither team is affected by the inter-team unreachability as far as convergence goes
      g.teamLeader("dc1", dc1a1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g.convergence("dc1", dc1a1.uniqueAddress, Set.empty) should ===(true)

      g.teamLeader("dc2", dc2c1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g.convergence("dc2", dc2c1.uniqueAddress, Set.empty) should ===(true)
    }

    "ignore cross team unreachability when determining inside of team reachability" in {
      val r1 = Reachability.empty
        .unreachable(dc1a1.uniqueAddress, dc2c1.uniqueAddress)
        .unreachable(dc2c1.uniqueAddress, dc1a1.uniqueAddress)

      val g = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1), overview = GossipOverview(reachability = r1))

      // inside of the teams we don't care about the cross team unreachability
      g.isReachable(dc1a1.uniqueAddress, dc1b1.uniqueAddress) should ===(true)
      g.isReachable(dc1b1.uniqueAddress, dc1a1.uniqueAddress) should ===(true)
      g.isReachable(dc2c1.uniqueAddress, dc2d1.uniqueAddress) should ===(true)
      g.isReachable(dc2d1.uniqueAddress, dc2c1.uniqueAddress) should ===(true)

      // between teams it matters though
      g.isReachable(dc1a1.uniqueAddress, dc2c1.uniqueAddress) should ===(false)
      g.isReachable(dc1b1.uniqueAddress, dc2c1.uniqueAddress) should ===(false)
      g.isReachable(dc2c1.uniqueAddress, dc1a1.uniqueAddress) should ===(false)
      g.isReachable(dc2d1.uniqueAddress, dc1a1.uniqueAddress) should ===(false)

      // between the two other nodes there is no unreachability
      g.isReachable(dc1b1.uniqueAddress, dc2d1.uniqueAddress) should ===(true)
      g.isReachable(dc2d1.uniqueAddress, dc1b1.uniqueAddress) should ===(true)
    }

    "not returning a downed team leader" in {
      val g = Gossip(members = SortedSet(dc1a1.copy(Down), dc1b1))
      g.leaderOf("dc1", g.members, dc1b1.uniqueAddress) should ===(Some(dc1b1.uniqueAddress))
    }

    "ignore cross team unreachability when determining team leader" in {
      val r1 = Reachability.empty
        .unreachable(dc1a1.uniqueAddress, dc2d1.uniqueAddress)
        .unreachable(dc2d1.uniqueAddress, dc1a1.uniqueAddress)

      val g = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1), overview = GossipOverview(reachability = r1))

      g.leaderOf("dc1", g.members, dc1a1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g.leaderOf("dc1", g.members, dc1b1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g.leaderOf("dc1", g.members, dc2c1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))
      g.leaderOf("dc1", g.members, dc2d1.uniqueAddress) should ===(Some(dc1a1.uniqueAddress))

      g.leaderOf("dc2", g.members, dc1a1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g.leaderOf("dc2", g.members, dc1b1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g.leaderOf("dc2", g.members, dc2c1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
      g.leaderOf("dc2", g.members, dc2d1.uniqueAddress) should ===(Some(dc2c1.uniqueAddress))
    }

    // TODO test coverage for when leaderOf returns None - I have not been able to figure it out

    "clear out a bunch of stuff when removing a node" in {
      val g = Gossip(members = SortedSet(dc1a1, dc1b1))
        .remove(dc1b1.uniqueAddress, System.currentTimeMillis())

      g.seenBy should not contain (dc1b1.uniqueAddress)
      g.overview.reachability.records.exists(_.observer == dc1b1.uniqueAddress) should be(false)
      g.overview.reachability.records.exists(_.subject == dc1b1.uniqueAddress) should be(false)
      g.version.versions should have size (0)
    }

    "not reintroduce members from out-of-team gossip when merging" in {
      // dc1 does not know about any unreachability nor that the node has been downed
      val gdc1 = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1))

      // dc2 has downed the dc2d1 node, seen it as unreachable and removed it
      val gdc2 = Gossip(members = SortedSet(dc1a1, dc1b1, dc2c1, dc2d1))
        .remove(dc2d1.uniqueAddress, System.currentTimeMillis())

      gdc2.tombstones.keys should contain(dc2d1.uniqueAddress)
      gdc2.members should not contain (dc2d1)
      gdc2.overview.reachability.records.filter(r ⇒ r.subject == dc2d1.uniqueAddress || r.observer == dc2d1.uniqueAddress) should be(empty)
      gdc2.overview.reachability.versions.keys should not contain (dc2d1.uniqueAddress)

      // when we merge the two, it should not be reintroduced
      val merged1 = gdc2 merge gdc1
      merged1.members should ===(SortedSet(dc1a1, dc1b1, dc2c1))

      merged1.tombstones.keys should contain(dc2d1.uniqueAddress)
      merged1.members should not contain (dc2d1)
      merged1.overview.reachability.records.filter(r ⇒ r.subject == dc2d1.uniqueAddress || r.observer == dc2d1.uniqueAddress) should be(empty)
      merged1.overview.reachability.versions.keys should not contain (dc2d1.uniqueAddress)

    }

    "prune old tombstones" in {
      val timestamp = 352684800
      val g = Gossip(members = SortedSet(dc1a1, dc1b1))
        .remove(dc1b1.uniqueAddress, timestamp)

      g.tombstones.keys should contain(dc1b1.uniqueAddress)

      val pruned = g.pruneTombstones(timestamp + 1)

      // when we merge the two, it should not be reintroduced
      pruned.tombstones.keys should not contain (dc2d1.uniqueAddress)
    }

    "mark a node as down" in {
      val g = Gossip(members = SortedSet(dc1a1, dc1b1))
        .seen(dc1a1.uniqueAddress)
        .seen(dc1b1.uniqueAddress)
        .markAsDown(dc1b1)

      g.member(dc1b1.uniqueAddress).status should ===(MemberStatus.Down)
      g.overview.seen should not contain (dc1b1.uniqueAddress)

      // obviously the other member should be unaffected
      g.member(dc1a1.uniqueAddress).status should ===(dc1a1.status)
      g.overview.seen should contain(dc1a1.uniqueAddress)
    }

    "update members" in {
      val joining = TestMember(Address("akka.tcp", "sys", "d", 2552), Joining, Set.empty, team = "dc2")
      val g = Gossip(members = SortedSet(dc1a1, joining))

      g.member(joining.uniqueAddress).status should ===(Joining)

      val updated = g.update(SortedSet(joining.copy(status = Up)))

      updated.member(joining.uniqueAddress).status should ===(Up)

      // obviously the other member should be unaffected
      updated.member(dc1a1.uniqueAddress).status should ===(dc1a1.status)
    }
  }
}
