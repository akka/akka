/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.cluster.ClusterEvent.{ CurrentClusterState, DataCenterReachabilityEvent, ReachableDataCenter, UnreachableDataCenter }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object MultiDcSplitBrainMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString(
    """
      akka.loglevel = DEBUG
      akka.cluster.debug.verbose-heartbeat-logging = on
      akka.remote.netty.tcp.connection-timeout = 5 s # speedup in case of connection issue
      akka.remote.retry-gate-closed-for = 1 s
      akka.cluster.multi-data-center {
        failure-detector {
          acceptable-heartbeat-pause = 4s
          heartbeat-interval = 1s
        }
      }
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(first, second)(ConfigFactory.parseString(
    """
      akka.cluster.multi-data-center.self-data-center = "dc1"
    """))

  nodeConfig(third, fourth)(ConfigFactory.parseString(
    """
      akka.cluster.multi-data-center.self-data-center = "dc2"
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
  var barrierCounter = 0

  def splitDataCenters(notMembers: Set[RoleName]): Unit = {
    val memberNodes = (dc1 ++ dc2).filterNot(notMembers)
    val probe = TestProbe()
    runOn(memberNodes: _*) {
      cluster.subscribe(probe.ref, classOf[DataCenterReachabilityEvent])
      probe.expectMsgType[CurrentClusterState]
    }
    enterBarrier(s"split-$barrierCounter")
    barrierCounter += 1

    runOn(first) {
      for (dc1Node ← dc1; dc2Node ← dc2) {
        testConductor.blackhole(dc1Node, dc2Node, Direction.Both).await
      }
    }

    enterBarrier(s"after-split-$barrierCounter")
    barrierCounter += 1

    runOn(memberNodes: _*) {
      probe.expectMsgType[UnreachableDataCenter](15.seconds)
      cluster.unsubscribe(probe.ref)
      runOn(dc1: _*) {
        awaitAssert {
          cluster.state.unreachableDataCenters should ===(Set("dc2"))
        }
      }
      runOn(dc2: _*) {
        awaitAssert {
          cluster.state.unreachableDataCenters should ===(Set("dc1"))
        }
      }
      cluster.state.unreachable should ===(Set.empty)
    }
    enterBarrier(s"after-split-verified-$barrierCounter")
    barrierCounter += 1
  }

  def unsplitDataCenters(notMembers: Set[RoleName]): Unit = {
    val memberNodes = (dc1 ++ dc2).filterNot(notMembers)
    val probe = TestProbe()
    runOn(memberNodes: _*) {
      cluster.subscribe(probe.ref, classOf[ReachableDataCenter])
      probe.expectMsgType[CurrentClusterState]
    }
    enterBarrier(s"unsplit-$barrierCounter")
    barrierCounter += 1

    runOn(first) {
      for (dc1Node ← dc1; dc2Node ← dc2) {
        testConductor.passThrough(dc1Node, dc2Node, Direction.Both).await
      }
    }

    enterBarrier(s"after-unsplit-$barrierCounter")
    barrierCounter += 1

    runOn(memberNodes: _*) {
      probe.expectMsgType[ReachableDataCenter](25.seconds)
      cluster.unsubscribe(probe.ref)
      awaitAssert {
        cluster.state.unreachableDataCenters should ===(Set.empty)
      }
    }
    enterBarrier(s"after-unsplit-verified-$barrierCounter")
    barrierCounter += 1

  }

  "A cluster with multiple data centers" must {
    "be able to form two data centers" in {
      awaitClusterUp(first, second, third)
    }

    "be able to have a data center member join while there is inter data center split" in within(20.seconds) {
      // introduce a split between data centers
      splitDataCenters(notMembers = Set(fourth))

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

      unsplitDataCenters(notMembers = Set.empty)

      runOn(dc1: _*) {
        awaitAssert(clusterView.members.collect {
          case m if m.dataCenter == "dc2" && m.status == MemberStatus.Up ⇒ m.address
        } should ===(Set(address(third), address(fourth))))
      }

      enterBarrier("inter-data-center-split-1-done")
    }

    "be able to have data center member leave while there is inter data center split" in within(20.seconds) {
      splitDataCenters(notMembers = Set.empty)

      runOn(fourth) {
        cluster.leave(fourth)
      }

      runOn(third) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("node-4-left")

      unsplitDataCenters(notMembers = Set(fourth))

      runOn(first, second) {
        awaitAssert(clusterView.members.filter(_.address == address(fourth)) should ===(Set.empty))
      }
      enterBarrier("inter-data-center-split-2-done")
    }

  }
}
