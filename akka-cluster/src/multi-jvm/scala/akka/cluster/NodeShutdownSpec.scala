/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
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
        auto-down = on
        failure-detector.threshold = 4
      }
    """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class NodeShutdownMultiJvmNode1 extends NodeShutdownSpec
class NodeShutdownMultiJvmNode2 extends NodeShutdownSpec

abstract class NodeShutdownSpec extends MultiNodeSpec(NodeShutdownMultiJvmSpec) with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import NodeShutdownMultiJvmSpec._

  override def initialParticipants = 2

  after {
    testConductor.enter("after")
  }

  "A cluster of 2 nodes" must {

    "not be singleton cluster when joined" taggedAs LongRunningTest in {
      // make sure that the node-to-join is started before other join
      runOn(first) {
        cluster.self
      }
      testConductor.enter("first-started")

      runOn(second) {
        cluster.join(node(first).address)
      }
      awaitUpConvergence(numberOfMembers = 2)
      cluster.isSingletonCluster must be(false)
    }

    "become singleton cluster when one node is shutdown" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        testConductor.shutdown(second, 0)
        testConductor.removeNode(second)
        awaitUpConvergence(numberOfMembers = 1, canNotBePartOfMemberRing = Seq(secondAddress), 30.seconds)
        cluster.isSingletonCluster must be(true)
        cluster.isLeader must be(true)
      }

    }
  }

}
