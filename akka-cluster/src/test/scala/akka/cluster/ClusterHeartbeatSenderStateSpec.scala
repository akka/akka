/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import akka.routing.ConsistentHash
import scala.concurrent.util.Deadline
import scala.concurrent.util.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterHeartbeatSenderStateSpec extends WordSpec with MustMatchers {

  val selfAddress = Address("akka", "sys", "myself", 2552)
  val aa = Address("akka", "sys", "aa", 2552)
  val bb = Address("akka", "sys", "bb", 2552)
  val cc = Address("akka", "sys", "cc", 2552)
  val dd = Address("akka", "sys", "dd", 2552)
  val ee = Address("akka", "sys", "ee", 2552)

  val emptyState = ClusterHeartbeatSenderState.empty(ConsistentHash(Seq.empty[Address], 10),
    selfAddress.toString, 3)

  "A ClusterHeartbeatSenderState" must {

    "return empty active set when no nodes" in {
      emptyState.active.isEmpty must be(true)
    }

    "include joinInProgress in active set" in {
      val s = emptyState.addJoinInProgress(aa, Deadline.now + 30.seconds)
      s.joinInProgress.keySet must be(Set(aa))
      s.active must be(Set(aa))
    }

    "remove joinInProgress from active set after removeOverdueJoinInProgress" in {
      val s = emptyState.addJoinInProgress(aa, Deadline.now - 30.seconds).removeOverdueJoinInProgress()
      s.joinInProgress must be(Map.empty)
      s.active must be(Set.empty)
      s.ending must be(Map(aa -> 0))
    }

    "remove joinInProgress after reset" in {
      val s = emptyState.addJoinInProgress(aa, Deadline.now + 30.seconds).reset(Set(aa, bb))
      s.joinInProgress must be(Map.empty)
    }

    "remove joinInProgress after addMember" in {
      val s = emptyState.addJoinInProgress(aa, Deadline.now + 30.seconds).addMember(aa)
      s.joinInProgress must be(Map.empty)
    }

    "remove joinInProgress after removeMember" in {
      val s = emptyState.addJoinInProgress(aa, Deadline.now + 30.seconds).reset(Set(aa, bb)).removeMember(aa)
      s.joinInProgress must be(Map.empty)
      s.ending must be(Map(aa -> 0))
    }

    "remove from ending after addJoinInProgress" in {
      val s = emptyState.reset(Set(aa, bb)).removeMember(aa)
      s.ending must be(Map(aa -> 0))
      val s2 = s.addJoinInProgress(aa, Deadline.now + 30.seconds)
      s2.joinInProgress.keySet must be(Set(aa))
      s2.ending must be(Map.empty)
    }

    "include nodes from reset in active set" in {
      val nodes = Set(aa, bb, cc)
      val s = emptyState.reset(nodes)
      s.all must be(nodes)
      s.current must be(nodes)
      s.ending must be(Map.empty)
      s.active must be(nodes)
    }

    "limit current nodes to monitoredByNrOfMembers when adding members" in {
      val nodes = Set(aa, bb, cc, dd)
      val s = nodes.foldLeft(emptyState) { _ addMember _ }
      s.all must be(nodes)
      s.current.size must be(3)
      s.addMember(ee).current.size must be(3)
    }

    "move meber to ending set when removing member" in {
      val nodes = Set(aa, bb, cc, dd, ee)
      val s = emptyState.reset(nodes)
      s.ending must be(Map.empty)
      val included = s.current.head
      val s2 = s.removeMember(included)
      s2.ending must be(Map(included -> 0))
      s2.current must not contain (included)
      val s3 = s2.addMember(included)
      s3.current must contain(included)
      s3.ending.keySet must not contain (included)
    }

    "increase ending count correctly" in {
      val s = emptyState.reset(Set(aa)).removeMember(aa)
      s.ending must be(Map(aa -> 0))
      val s2 = s.increaseEndingCount(aa).increaseEndingCount(aa)
      s2.ending must be(Map(aa -> 2))
    }

  }
}
