/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.duration._

object NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(
    "akka.cluster.auto-down-unreachable-after = 0s")).
    withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode1 extends NodeLeavingAndExitingAndBeingRemovedSpec
class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode2 extends NodeLeavingAndExitingAndBeingRemovedSpec
class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode3 extends NodeLeavingAndExitingAndBeingRemovedSpec

abstract class NodeLeavingAndExitingAndBeingRemovedSpec
  extends MultiNodeSpec(NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec._

  "A node that is LEAVING a non-singleton cluster" must {

    "eventually set to REMOVED and removed from membership ring and seen table" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      within(30.seconds) {
        runOn(first) {
          cluster.leave(second)
        }
        enterBarrier("second-left")

        runOn(first, third) {
          enterBarrier("second-shutdown")
          markNodeAsUnavailable(second)
          // verify that the 'second' node is no longer part of the 'members'/'unreachable' set
          awaitAssert {
            clusterView.members.map(_.address) should not contain (address(second))
          }
          awaitAssert {
            clusterView.unreachableMembers.map(_.address) should not contain (address(second))
          }
        }

        runOn(second) {
          // verify that the second node is shut down
          awaitCond(cluster.isTerminated)
          enterBarrier("second-shutdown")
        }
      }

      enterBarrier("finished")
    }
  }
}
