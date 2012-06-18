/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object NodeLeavingAndExitingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
        akka.cluster {
          leader-actions-interval           = 5 s  # increase the leader action task frequency to make sure we get a chance to test the LEAVING state
          unreachable-nodes-reaper-interval = 300 s # turn "off"
        }
      """)
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class NodeLeavingAndExitingMultiJvmNode1 extends NodeLeavingAndExitingSpec with FailureDetectorPuppetStrategy
class NodeLeavingAndExitingMultiJvmNode2 extends NodeLeavingAndExitingSpec with FailureDetectorPuppetStrategy
class NodeLeavingAndExitingMultiJvmNode3 extends NodeLeavingAndExitingSpec with FailureDetectorPuppetStrategy

abstract class NodeLeavingAndExitingSpec
  extends MultiNodeSpec(NodeLeavingAndExitingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeLeavingAndExitingMultiJvmSpec._

  "A node that is LEAVING a non-singleton cluster" must {

    // FIXME make it work and remove ignore
    "be moved to EXITING by the leader" taggedAs LongRunningTest ignore {

      awaitClusterUp(first, second, third)

      runOn(first) {
        cluster.leave(second)
      }
      testConductor.enter("second-left")

      runOn(first, third) {

        // 1. Verify that 'second' node is set to LEAVING
        //   We have set the 'leader-actions-interval' to 5 seconds to make sure that we get a
        //   chance to test the LEAVING state before the leader moves the node to EXITING
        awaitCond(cluster.latestGossip.members.exists(_.status == MemberStatus.Leaving)) // wait on LEAVING
        val hasLeft = cluster.latestGossip.members.find(_.status == MemberStatus.Leaving) // verify node that left
        hasLeft must be('defined)
        hasLeft.get.address must be(address(second))

        // 2. Verify that 'second' node is set to EXITING
        awaitCond(cluster.latestGossip.members.exists(_.status == MemberStatus.Exiting)) // wait on EXITING
        val hasExited = cluster.latestGossip.members.find(_.status == MemberStatus.Exiting) // verify node that exited
        hasExited must be('defined)
        hasExited.get.address must be(address(second))
      }

      testConductor.enter("finished")
    }
  }
}
