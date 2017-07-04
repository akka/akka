/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object MultiDcSplitBrainMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(MultiNodeClusterSpec.clusterConfig)

  nodeConfig(first, second)(ConfigFactory.parseString(
    """
      akka.cluster.data-center = "dc1"
      akka.loglevel = INFO
    """))

  nodeConfig(third, fourth)(ConfigFactory.parseString(
    """
      akka.cluster.data-center = "dc2"
      akka.loglevel = INFO
    """))

  testTransport(on = true)
}

class MultiDcSplitBrainMultiJvmNode1 extends MultiDcSpec
class MultiDcSplitBrainMultiJvmNode2 extends MultiDcSpec
class MultiDcSplitBrainMultiJvmNode3 extends MultiDcSpec
class MultiDcSplitBrainMultiJvmNode4 extends MultiDcSpec
class MultiDcSplitBrainMultiJvmNode5 extends MultiDcSpec

abstract class MultiDcSplitBrainSpec
  extends MultiNodeSpec(MultiDcSplitBrainMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MultiDcSplitBrainMultiJvmSpec._

  val dc1 = List(first, second)
  val dc2 = List(third, fourth)

  def splitDataCenters(): Unit = {
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

  def unsplitDataCenters(): Unit = {
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

  "A cluster with multiple data centers" must {
    "be able to form two data centers" in {
      awaitClusterUp(first, second, third)
    }

    "be able to have a data center member join while there is inter data center split" in within(20.seconds) {
      // introduce a split between data centers
      splitDataCenters()
      enterBarrier("data-center-split-1")

      runOn(fourth) {
        cluster.join(third)
      }
      enterBarrier("inter-data-center unreachability")

      // should be able to join and become up since the
      // split is between dc1 and dc2
      runOn(third, fourth) {
        awaitAssert(clusterView.members.collect {
          case m if m.dataCenter == "dc2" && m.status == MemberStatus.Up ⇒ m.address
        }) should ===(Set(address(third), address(fourth)))
      }
      enterBarrier("dc2-join-completed")

      unsplitDataCenters()
      enterBarrier("data-center-unsplit-1")

      runOn(dc1: _*) {
        awaitAssert(clusterView.members.collect {
          case m if m.dataCenter == "dc2" && m.status == MemberStatus.Up ⇒ m.address
        }) should ===(Set(address(third), address(fourth)))
      }

      enterBarrier("inter-data-center-split-1-done")
    }

    "be able to have data center member leave while there is inter data center split" in within(20.seconds) {
      splitDataCenters()
      enterBarrier("data-center-split-2")

      runOn(fourth) {
        cluster.leave(third)
      }

      runOn(third, fourth) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("node-4-left")

      unsplitDataCenters()
      enterBarrier("data-center-unsplit-2")

      runOn(first, second) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("inter-data-center-split-2-done")
    }

  }
}
