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

object NodeLeavingAndExitingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
    .withFallback(ConfigFactory.parseString("""
        akka.cluster {
          leader-actions-frequency           = 5 s  # increase the leader action task frequency
          unreachable-nodes-reaper-frequency = 30 s # turn "off" reaping to unreachable node set
        }
      """)
    .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class NodeLeavingAndExitingMultiJvmNode1 extends NodeLeavingAndExitingSpec
class NodeLeavingAndExitingMultiJvmNode2 extends NodeLeavingAndExitingSpec
class NodeLeavingAndExitingMultiJvmNode3 extends NodeLeavingAndExitingSpec

abstract class NodeLeavingAndExitingSpec extends MultiNodeSpec(NodeLeavingAndExitingMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import NodeLeavingAndExitingMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  "A node that is LEAVING a non-singleton cluster" must {

    "be moved to EXITING by the leader" taggedAs LongRunningTest in {

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

        // 1. Verify that 'second' node is set to LEAVING
        awaitCond(cluster.latestGossip.members.exists(_.status == MemberStatus.Leaving)) // wait on LEAVING
        val hasLeft = cluster.latestGossip.members.find(_.status == MemberStatus.Leaving) // verify node that left
        hasLeft must be('defined)
        hasLeft.get.address must be(secondAddress)

        // 2. Verify that 'second' node is set to EXITING
        awaitCond(cluster.latestGossip.members.exists(_.status == MemberStatus.Exiting)) // wait on EXITING
        val hasExited = cluster.latestGossip.members.find(_.status == MemberStatus.Exiting) // verify node that exited
        hasExited must be('defined)
        hasExited.get.address must be(secondAddress)
      }

      testConductor.enter("finished")
    }
  }
}
