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

object NodeLeavingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
    .withFallback(ConfigFactory.parseString("""
        akka.cluster.unreachable-nodes-reaper-frequency = 30 s # turn "off" reaping to unreachable node set
      """))
    .withFallback(MultiNodeClusterSpec.clusterConfig))
}

class NodeLeavingMultiJvmNode1 extends NodeLeavingSpec
class NodeLeavingMultiJvmNode2 extends NodeLeavingSpec
class NodeLeavingMultiJvmNode3 extends NodeLeavingSpec

abstract class NodeLeavingSpec extends MultiNodeSpec(NodeLeavingMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import NodeLeavingMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  "A node that is LEAVING a non-singleton cluster" must {

    "be marked as LEAVING in the converged membership table" taggedAs LongRunningTest in {

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
        awaitCond(cluster.latestGossip.members.exists(_.status == MemberStatus.Leaving))

        val hasLeft = cluster.latestGossip.members.find(_.status == MemberStatus.Leaving)
        hasLeft must be('defined)
        hasLeft.get.address must be(secondAddress)
      }

      testConductor.enter("finished")
    }
  }
}
