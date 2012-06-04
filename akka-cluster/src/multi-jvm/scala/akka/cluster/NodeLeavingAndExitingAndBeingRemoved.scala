/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import org.scalatest.BeforeAndAfter
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode1 extends NodeLeavingAndExitingAndBeingRemovedSpec
class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode2 extends NodeLeavingAndExitingAndBeingRemovedSpec
class NodeLeavingAndExitingAndBeingRemovedMultiJvmNode3 extends NodeLeavingAndExitingAndBeingRemovedSpec

abstract class NodeLeavingAndExitingAndBeingRemovedSpec extends MultiNodeSpec(NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import NodeLeavingAndExitingAndBeingRemovedMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  val reaperWaitingTime = 30.seconds.dilated

  "A node that is LEAVING a non-singleton cluster" must {

    "be moved to EXITING and then to REMOVED by the reaper" taggedAs LongRunningTest in {

      runOn(first) {
        cluster.self
      }
      testConductor.enter("first-started")

      runOn(second, third) {
        cluster.join(firstAddress)
      }
      awaitUpConvergence(numberOfMembers = 3)
      testConductor.enter("rest-started")

      runOn(first) {
        cluster.leave(secondAddress)
      }
      testConductor.enter("second-left")

      runOn(first, third) {
        // verify that the 'second' node is no longer part of the 'members' set
        awaitCond(cluster.latestGossip.members.forall(_.address != secondAddress), reaperWaitingTime)

        // verify that the 'second' node is part of the 'unreachable' set
        awaitCond(cluster.latestGossip.overview.unreachable.exists(_.status == MemberStatus.Removed), reaperWaitingTime)

        // verify node that got removed is 'second' node
        val isRemoved = cluster.latestGossip.overview.unreachable.find(_.status == MemberStatus.Removed)
        isRemoved must be('defined)
        isRemoved.get.address must be(secondAddress)
      }

      testConductor.enter("finished")
    }
  }
}
