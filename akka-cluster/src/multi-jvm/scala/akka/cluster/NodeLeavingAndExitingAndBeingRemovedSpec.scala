/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._

object NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode1 extends NodeLeavingAndExitingAndBeingRemovedSpec with FailureDetectorPuppetStrategy
class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode2 extends NodeLeavingAndExitingAndBeingRemovedSpec with FailureDetectorPuppetStrategy
class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode3 extends NodeLeavingAndExitingAndBeingRemovedSpec with FailureDetectorPuppetStrategy

abstract class NodeLeavingAndExitingAndBeingRemovedSpec
  extends MultiNodeSpec(NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec._

  val reaperWaitingTime = 30.seconds.dilated

  "A node that is LEAVING a non-singleton cluster" must {

    "eventually set to REMOVED by the reaper, and removed from membership ring and seen table" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first) {
        cluster.leave(second)
      }
      enterBarrier("second-left")

      runOn(first, third) {
        // verify that the 'second' node is no longer part of the 'members' set
        awaitCond(cluster.members.forall(_.address != address(second)), reaperWaitingTime)

        // verify that the 'second' node is not part of the 'unreachable' set
        awaitCond(cluster.unreachableMembers.forall(_.address != address(second)), reaperWaitingTime)
      }

      runOn(second) {
        // verify that the second node is shut down and has status REMOVED
        awaitCond(!cluster.isRunning, reaperWaitingTime)
        awaitCond(cluster.status == MemberStatus.Removed, reaperWaitingTime)
      }

      enterBarrier("finished")
    }
  }
}
