/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address
import akka.routing.ConsistentHash
import scala.concurrent.duration._
import scala.collection.immutable
import scala.collection.immutable.HashSet

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterHeartbeatSenderStateSpec extends WordSpec with MustMatchers {

  val selfAddress = Address("akka.tcp", "sys", "myself", 2552)
  val aa = Address("akka.tcp", "sys", "aa", 2552)
  val bb = Address("akka.tcp", "sys", "bb", 2552)
  val cc = Address("akka.tcp", "sys", "cc", 2552)
  val dd = Address("akka.tcp", "sys", "dd", 2552)
  val ee = Address("akka.tcp", "sys", "ee", 2552)

  val emptyState = ClusterHeartbeatSenderState.empty(selfAddress, 3)

  "A ClusterHeartbeatSenderState" must {

    "return empty active set when no nodes" in {
      emptyState.active.isEmpty must be(true)
    }

    "include heartbeatRequest in active set" in {
      val s = emptyState.addHeartbeatRequest(aa, Deadline.now + 30.seconds)
      s.heartbeatRequest.keySet must be(Set(aa))
      s.active must be(Set(aa))
    }

    "remove heartbeatRequest from active set after removeOverdueHeartbeatRequest" in {
      val s = emptyState.addHeartbeatRequest(aa, Deadline.now - 30.seconds).removeOverdueHeartbeatRequest()
      s.heartbeatRequest must be(Map.empty)
      s.active must be(Set.empty)
      s.ending must be(Set(aa))
    }

    "remove heartbeatRequest after reset" in {
      val s = emptyState.addHeartbeatRequest(aa, Deadline.now + 30.seconds).reset(HashSet(aa, bb))
      s.heartbeatRequest must be(Map.empty)
    }

    "remove heartbeatRequest after addMember" in {
      val s = emptyState.addHeartbeatRequest(aa, Deadline.now + 30.seconds).addMember(aa)
      s.heartbeatRequest must be(Map.empty)
    }

    "remove heartbeatRequest after removeMember" in {
      val s = emptyState.addHeartbeatRequest(aa, Deadline.now + 30.seconds).reset(HashSet(aa, bb)).removeMember(aa)
      s.heartbeatRequest must be(Map.empty)
      s.ending must be(Set(aa))
    }

    "remove from ending after addHeartbeatRequest" in {
      val s = emptyState.reset(HashSet(aa, bb)).removeMember(aa)
      s.ending must be(Set(aa))
      val s2 = s.addHeartbeatRequest(aa, Deadline.now + 30.seconds)
      s2.heartbeatRequest.keySet must be(Set(aa))
      s2.ending must be(Set.empty)
    }

    "include nodes from reset in active set" in {
      val nodes = HashSet(aa, bb, cc)
      val s = emptyState.reset(nodes)
      s.current must be(nodes)
      s.ending must be(Set.empty)
      s.active must be(nodes)
    }

    "limit current nodes to monitoredByNrOfMembers when adding members" in {
      val nodes = Set(aa, bb, cc, dd)
      val s = nodes.foldLeft(emptyState) { _ addMember _ }
      s.current.size must be(3)
      s.addMember(ee).current.size must be(3)
    }

    "move member to ending set when removing member" in {
      val nodes = HashSet(aa, bb, cc, dd, ee)
      val s = emptyState.reset(nodes)
      s.ending must be(Set.empty)
      val included = s.current.head
      val s2 = s.removeMember(included)
      s2.ending must be(Set(included))
      s2.current must not contain (included)
      val s3 = s2.addMember(included)
      s3.current must contain(included)
      s3.ending must not contain (included)
    }

    "remove ending correctly" in {
      val s = emptyState.reset(HashSet(aa)).removeMember(aa)
      s.ending must be(Set(aa))
      val s2 = s.removeEnding(aa)
      s2.ending must be(Set.empty)
    }

  }
}
