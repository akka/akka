/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.cluster.MemberStatus.Removed
import akka.cluster.NodeDowningAndBeingRemovedMultiJvmSpec.first
import akka.cluster.NodeDowningAndBeingRemovedMultiJvmSpec.second
import akka.cluster.NodeDowningAndBeingRemovedMultiJvmSpec.third
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

object ClusterShutdownSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
       # important config
      """).withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class ClusterShutdownSpecMultiJvmNode1 extends ClusterShutdownSpec
class ClusterShutdownSpecMultiJvmNode2 extends ClusterShutdownSpec
class ClusterShutdownSpecMultiJvmNode3 extends ClusterShutdownSpec

// FIXME test for a new node
abstract class ClusterShutdownSpec
    extends MultiNodeSpec(ClusterShutdownSpec)
    with MultiNodeClusterSpec
    with Eventually {

  "Cluster shutdown" should {
    "form cluster" in {
      awaitClusterUp(first, second, third)
    }
    enterBarrier("cluster-up")
    "shutdown" in {
      runOn(first) {
        Cluster(system).prepareForFullClusterShutdown()
      }
      awaitAssert({
        withClue("members: " + Cluster(system).readView.members) {
          Cluster(system).selfMember.status shouldEqual MemberStatus.ReadyForShutdown
        }
      }, 10.seconds)
    }
    "finish" in {
      awaitAssert {
        Cluster(system).readView.members.map(_.status) shouldEqual Set(MemberStatus.ReadyForShutdown)
      }
      enterBarrier("done")
    }
    "be allowed to leave" in {
      runOn(first) {
        Cluster(system).leave(address(first))
      }
      awaitAssert({
        withClue("members: " + Cluster(system).readView.members) {
          runOn(second, third) {
            Cluster(system).readView.members.size shouldEqual 2
          }
          runOn(first) {
            Cluster(system).selfMember.status shouldEqual Removed
          }
        }
      }, 10.seconds)
      enterBarrier("first-gone")
      runOn(second) {
        Cluster(system).leave(address(second))
        Cluster(system).leave(address(third))
      }
      awaitAssert({
        withClue("self member: " + Cluster(system).selfMember) {
          Cluster(system).selfMember.status shouldEqual Removed
        }
      }, 10.seconds)
      enterBarrier("all-gone")
    }
  }
}
