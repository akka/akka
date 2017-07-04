/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object MultiTeamSplitBrainMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(MultiNodeClusterSpec.clusterConfig)

  nodeConfig(first, second)(ConfigFactory.parseString(
    """
      akka.cluster.team = "dc1"
      akka.loglevel = INFO
    """))

  nodeConfig(third, fourth)(ConfigFactory.parseString(
    """
      akka.cluster.team = "dc2"
      akka.loglevel = INFO
    """))

  testTransport(on = true)
}

class MultiTeamSplitBrainMultiJvmNode1 extends MultiTeamSpec
class MultiTeamSplitBrainMultiJvmNode2 extends MultiTeamSpec
class MultiTeamSplitBrainMultiJvmNode3 extends MultiTeamSpec
class MultiTeamSplitBrainMultiJvmNode4 extends MultiTeamSpec
class MultiTeamSplitBrainMultiJvmNode5 extends MultiTeamSpec

abstract class MultiTeamSplitBrainSpec
  extends MultiNodeSpec(MultiTeamSplitBrainMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MultiTeamSplitBrainMultiJvmSpec._

  val dc1 = List(first, second)
  val dc2 = List(third, fourth)

  def splitTeams(): Unit = {
    runOn(first) {
      for {
        dc1Node ← dc1
        dc2Node ← dc2
      } {
        testConductor.blackhole(dc1Node, dc2Node, Direction.Both).await
      }
    }

    runOn(dc1: _*) {
      awaitAssert(clusterView.unreachableMembers.map(_.address) should ===(dc2.map(address)))
    }
    runOn(dc2: _*) {
      awaitAssert(clusterView.unreachableMembers.map(_.address) should ===(dc1.map(address)))
    }

  }

  def unsplitTeams(): Unit = {
    runOn(first) {
      for {
        dc1Node ← dc1
        dc2Node ← dc2
      } {
        testConductor.passThrough(dc1Node, dc2Node, Direction.Both).await
      }
    }

    awaitAllReachable()
  }

  "A cluster with multiple cluster teams" must {
    "be able to form two teams" in {
      awaitClusterUp(first, second, third)
    }

    "be able to have a team member join while there is inter-team split" in within(20.seconds) {
      // introduce a split between teams
      splitTeams()
      enterBarrier("team-split-1")

      runOn(fourth) {
        cluster.join(third)
      }
      enterBarrier("inter-team unreachability")

      // should be able to join and become up since the
      // split is between dc1 and dc2
      runOn(third, fourth) {
        awaitAssert(clusterView.members.collect {
          case m if m.team == "dc2" && m.status == MemberStatus.Up ⇒ m.address
        }) should ===(Set(address(third), address(fourth)))
      }
      enterBarrier("dc2-join-completed")

      unsplitTeams()
      enterBarrier("team-unsplit-1")

      runOn(dc1: _*) {
        awaitAssert(clusterView.members.collect {
          case m if m.team == "dc2" && m.status == MemberStatus.Up ⇒ m.address
        }) should ===(Set(address(third), address(fourth)))
      }

      enterBarrier("inter-team-split-1-done")
    }

    "be able to have team member leave while there is inter-team split" in within(20.seconds) {
      splitTeams()
      enterBarrier("team-split-2")

      runOn(fourth) {
        cluster.leave(third)
      }

      runOn(third, fourth) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("node-4-left")

      unsplitTeams()
      enterBarrier("team-unsplit-2")

      runOn(first, second) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("inter-team-split-2-done")
    }

  }
}
