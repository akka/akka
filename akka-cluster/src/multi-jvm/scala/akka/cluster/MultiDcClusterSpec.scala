/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.cluster.MemberStatus.Up
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object MultiDcMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(MultiNodeClusterSpec.clusterConfig)

  nodeConfig(first, second)(ConfigFactory.parseString(
    """
      akka.cluster.data-center = "dc1"
      akka.loglevel = INFO
    """))

  nodeConfig(third, fourth, fifth)(ConfigFactory.parseString(
    """
      akka.cluster.data-center = "dc2"
      akka.loglevel = INFO
    """))

  testTransport(on = true)
}

class MultiDcMultiJvmNode1 extends MultiDcSpec
class MultiDcMultiJvmNode2 extends MultiDcSpec
class MultiDcMultiJvmNode3 extends MultiDcSpec
class MultiDcMultiJvmNode4 extends MultiDcSpec
class MultiDcMultiJvmNode5 extends MultiDcSpec

abstract class MultiDcSpec
  extends MultiNodeSpec(MultiDcMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MultiDcMultiJvmSpec._

  "A cluster with multiple data centers" must {
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

    "have a leader per data center" in {
      runOn(first, second) {
        cluster.settings.DataCenter should ===("dc1")
        clusterView.leader shouldBe defined
        val dc1 = Set(address(first), address(second))
        dc1 should contain(clusterView.leader.get)
      }
      runOn(third, fourth) {
        cluster.settings.DataCenter should ===("dc2")
        clusterView.leader shouldBe defined
        val dc2 = Set(address(third), address(fourth))
        dc2 should contain(clusterView.leader.get)
      }

      enterBarrier("leader per data center")
    }

    "be able to have data center member changes while there is inter data center unreachability" in within(20.seconds) {
      runOn(first) {
        testConductor.blackhole(first, third, Direction.Both).await
      }
      runOn(first, second, third, fourth) {
        awaitAssert(clusterView.reachability.allUnreachable should not be empty)
      }
      enterBarrier("inter-data-center unreachability")

      runOn(fifth) {
        cluster.join(third)
      }

      // should be able to join and become up since the
      // unreachable is between dc1 and dc2,
      within(10.seconds) {
        awaitAssert(clusterView.members.filter(_.status == MemberStatus.Up) should have size (5))
      }

      runOn(first) {
        testConductor.passThrough(first, third, Direction.Both).await
      }
      runOn(first, second, third, fourth) {
        awaitAssert(clusterView.reachability.allUnreachable should not be empty)
      }
      enterBarrier("inter-data-center unreachability end")
    }

    "be able to have data center member changes while there is unreachability in another data center" in within(20.seconds) {
      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
      }
      runOn(first, second, third, fourth) {
        awaitAssert(clusterView.reachability.allUnreachable should not be empty)
      }
      enterBarrier("other-data-center-internal-unreachable")

      runOn(third) {
        cluster.join(fifth)
        // should be able to join and leave
        // since the unreachable nodes are inside of dc1
        cluster.leave(fourth)

        awaitAssert(clusterView.members.map(_.address) should not contain (address(fourth)))
        awaitAssert(clusterView.members.collect { case m if m.status == Up ⇒ m.address } should contain(address(fifth)))
      }

      enterBarrier("other-data-center-internal-unreachable changed")

      runOn(first) {
        testConductor.passThrough(first, second, Direction.Both).await
      }
      enterBarrier("other-datac-enter-internal-unreachable end")
    }

    "be able to down a member of another data-center" in within(20.seconds) {
      runOn(fifth) {
        cluster.down(address(second))
      }

      runOn(first, third, fifth) {
        awaitAssert(clusterView.members.map(_.address) should not contain (address(second)))
      }
      enterBarrier("cross-data-center-downed")
    }

  }
}
