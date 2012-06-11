/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object NodeShutdownMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster {
        auto-down                  = on
        failure-detector.threshold = 4
      }
    """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class NodeShutdownWithFailureDetectorPuppetMultiJvmNode1 extends NodeShutdownSpec with FailureDetectorPuppetStrategy
class NodeShutdownWithFailureDetectorPuppetMultiJvmNode2 extends NodeShutdownSpec with FailureDetectorPuppetStrategy

class NodeShutdownWithAccrualFailureDetectorMultiJvmNode1 extends NodeShutdownSpec with AccrualFailureDetectorStrategy
class NodeShutdownWithAccrualFailureDetectorMultiJvmNode2 extends NodeShutdownSpec with AccrualFailureDetectorStrategy

abstract class NodeShutdownSpec
  extends MultiNodeSpec(NodeShutdownMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeShutdownMultiJvmSpec._

  "A cluster of 2 nodes" must {

    "not be singleton cluster when joined" taggedAs LongRunningTest in {
      awaitClusterUp(first, second)
      cluster.isSingletonCluster must be(false)
      assertLeader(first, second)

      testConductor.enter("after-1")
    }

    "become singleton cluster when one node is shutdown" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        testConductor.shutdown(second, 0)

        markNodeAsUnavailable(secondAddress)

        awaitUpConvergence(numberOfMembers = 1, canNotBePartOfMemberRing = Seq(secondAddress), 30.seconds)
        cluster.isSingletonCluster must be(true)
        assertLeader(first)
      }

      testConductor.enter("after-2")
    }
  }
}
