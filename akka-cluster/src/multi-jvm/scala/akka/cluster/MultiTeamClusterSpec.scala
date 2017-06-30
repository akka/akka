/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object MultiTeamMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(MultiNodeClusterSpec.clusterConfig)

  nodeConfig(first, second)(ConfigFactory.parseString(
    """
      akka.cluster.team = "dc1"
      akka.loglevel = INFO
    """))

  nodeConfig(third, fourth, fifth)(ConfigFactory.parseString(
    """
      akka.cluster.team = "dc2"
      akka.loglevel = INFO
    """))

  testTransport(on = true)
}

class MultiTeamMultiJvmNode1 extends MultiTeamSpec
class MultiTeamMultiJvmNode2 extends MultiTeamSpec
class MultiTeamMultiJvmNode3 extends MultiTeamSpec
class MultiTeamMultiJvmNode4 extends MultiTeamSpec
class MultiTeamMultiJvmNode5 extends MultiTeamSpec

abstract class MultiTeamSpec
  extends MultiNodeSpec(MultiTeamMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MultiTeamMultiJvmSpec._

  "A cluster with multiple cluster teams" must {
    "be able to form" in {

      runOn(first) {
        cluster.join(first)
      }
      runOn(second, third, fourth) {
        cluster.join(first)
      }
      enterBarrier("form-cluster-join-attempt")

      runOn(first, second, third, fourth) {
        within(20.seconds) {
          awaitAssert(clusterView.members.filter(_.status == MemberStatus.Up) should have size (4))
        }
      }

      enterBarrier("cluster started")
    }

    "have a leader per team" in {
      runOn(first, second) {
        cluster.settings.Team should ===("dc1")
        clusterView.leader shouldBe defined
        val dc1 = Set(address(first), address(second))
        dc1 should contain(clusterView.leader.get)
      }
      runOn(third, fourth) {
        cluster.settings.Team should ===("dc2")
        clusterView.leader shouldBe defined
        val dc2 = Set(address(third), address(fourth))
        dc2 should contain(clusterView.leader.get)
      }

      enterBarrier("leader per team")
    }

    "be able to have team member changes while there is inter-team unreachability" in within(20.seconds) {
      runOn(first) {
        testConductor.blackhole(first, third, Direction.Both)
      }
      runOn(first, second, third, fourth) {
        awaitAssert(clusterView.unreachableMembers should not be empty)
      }
      enterBarrier("inter-team unreachability")

      runOn(fifth) {
        cluster.join(third)
      }

      // should be able to join and become up since the
      // unreachable is between dc1 and dc2,
      within(10.seconds) {
        awaitAssert(clusterView.members.filter(_.status == MemberStatus.Up) should have size (5))
      }

      runOn(first) {
        testConductor.passThrough(first, third, Direction.Both)
      }
      runOn(first, second, third, fourth) {
        awaitAssert(clusterView.unreachableMembers should not be empty)
      }
      enterBarrier("end")
    }

    "be able to have team member changes while there is unreachability in another team" in within(20.seconds) {
      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both)
      }
      runOn(first, second, third, fourth) {
        awaitAssert(clusterView.unreachableMembers should not be empty)
      }
      enterBarrier("other-team-internal-unreachable")

      runOn(fifth) {
        cluster.leave(first)
      }

      // should be able to leave and become removed
      // since the unreachable nodes are inside of dc1
      awaitAssert(clusterView.members.find(_.status == MemberStatus.Removed))
      enterBarrier("removed-seen")
    }

  }
}
