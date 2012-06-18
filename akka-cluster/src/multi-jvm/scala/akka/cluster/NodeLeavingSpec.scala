/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
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
      .withFallback(ConfigFactory.parseString("akka.cluster.unreachable-nodes-reaper-frequency = 30 s"))
      .withFallback(MultiNodeClusterSpec.clusterConfig))
}

class NodeLeavingMultiJvmNode1 extends NodeLeavingSpec with FailureDetectorPuppetStrategy
class NodeLeavingMultiJvmNode2 extends NodeLeavingSpec with FailureDetectorPuppetStrategy
class NodeLeavingMultiJvmNode3 extends NodeLeavingSpec with FailureDetectorPuppetStrategy

abstract class NodeLeavingSpec
  extends MultiNodeSpec(NodeLeavingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeLeavingMultiJvmSpec._

  "A node that is LEAVING a non-singleton cluster" must {

    // FIXME make it work and remove ignore
    "be marked as LEAVING in the converged membership table" taggedAs LongRunningTest ignore {

      awaitClusterUp(first, second, third)

      runOn(first) {
        cluster.leave(second)
      }
      testConductor.enter("second-left")

      runOn(first, third) {
        awaitCond(cluster.latestGossip.members.exists(_.status == MemberStatus.Leaving))

        val hasLeft = cluster.latestGossip.members.find(_.status == MemberStatus.Leaving)
        hasLeft must be('defined)
        hasLeft.get.address must be(address(second))
      }

      testConductor.enter("finished")
    }
  }
}
