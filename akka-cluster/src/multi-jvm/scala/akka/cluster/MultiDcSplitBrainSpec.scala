/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object MultiDcSplitBrainMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString(
    """
      akka.loglevel = INFO
      akka.cluster.run-coordinated-shutdown-when-down = off
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(first, second)(ConfigFactory.parseString(
    """
      akka.cluster.data-center = "dc1"
    """))

  nodeConfig(third, fourth)(ConfigFactory.parseString(
    """
      akka.cluster.data-center = "dc2"
    """))

  testTransport(on = true)
}

class MultiDcSplitBrainMultiJvmNode1 extends MultiDcSplitBrainSpec
class MultiDcSplitBrainMultiJvmNode2 extends MultiDcSplitBrainSpec
class MultiDcSplitBrainMultiJvmNode3 extends MultiDcSplitBrainSpec
class MultiDcSplitBrainMultiJvmNode4 extends MultiDcSplitBrainSpec

abstract class MultiDcSplitBrainSpec
  extends MultiNodeSpec(MultiDcSplitBrainMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MultiDcSplitBrainMultiJvmSpec._

  val dc1 = List(first, second)
  val dc2 = List(third, fourth)

  def splitDataCenters(dc1: Seq[RoleName], dc2: Seq[RoleName]): Unit = {
    runOn(first) {
      for {
        dc1Node ← dc1
        dc2Node ← dc2
      } {
        testConductor.blackhole(dc1Node, dc2Node, Direction.Both).await
      }
    }

    runOn(dc1: _*) {
      awaitAssert(clusterView.unreachableMembers.map(_.address) should contain allElementsOf (dc2.map(address)))
    }
    runOn(dc2: _*) {
      awaitAssert(clusterView.unreachableMembers.map(_.address) should contain allElementsOf (dc1.map(address)))
    }

  }

  def unsplitDataCenters(dc1: Seq[RoleName], dc2: Seq[RoleName]): Unit = {
    runOn(first) {
      for {
        dc1Node ← dc1
        dc2Node ← dc2
      } {
        testConductor.passThrough(dc1Node, dc2Node, Direction.Both).await
      }
    }

    runOn(dc1 ++ dc2: _*) {
      awaitAssert(clusterView.unreachableMembers.map(_.address) should be(empty))
    }
  }

  "A cluster with multiple data centers" must {
    "be able to form two data centers" in {
      awaitClusterUp(first, second, third)
    }

    "be able to have a data center member join while there is inter data center split" in within(20.seconds) {
      // introduce a split between data centers
      splitDataCenters(dc1 = List(first, second), dc2 = List(third))
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
        } should ===(Set(address(third), address(fourth))))
      }
      enterBarrier("dc2-join-completed")

      unsplitDataCenters(dc1 = List(first, second), dc2 = List(third))
      enterBarrier("data-center-unsplit-1")

      runOn(dc1: _*) {
        awaitAssert(clusterView.members.collect {
          case m if m.dataCenter == "dc2" && m.status == MemberStatus.Up ⇒ m.address
        } should ===(Set(address(third), address(fourth))))
      }

      enterBarrier("inter-data-center-split-1-done")
    }

    "be able to have data center member leave while there is inter data center split" in within(20.seconds) {
      splitDataCenters(dc1, dc2)
      enterBarrier("data-center-split-2")

      runOn(fourth) {
        cluster.leave(fourth)
      }

      runOn(third) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("node-4-left")

      unsplitDataCenters(dc1, List(third))
      enterBarrier("data-center-unsplit-2")

      runOn(first, second) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("inter-data-center-split-2-done")
    }

  }
}
