/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import org.scalatest.BeforeAndAfter
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.util.duration._
import akka.testkit._

object GossipingAccrualFailureDetectorMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.failure-detector.threshold=4")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class GossipingAccrualFailureDetectorMultiJvmNode1 extends GossipingAccrualFailureDetectorSpec
class GossipingAccrualFailureDetectorMultiJvmNode2 extends GossipingAccrualFailureDetectorSpec
class GossipingAccrualFailureDetectorMultiJvmNode3 extends GossipingAccrualFailureDetectorSpec

abstract class GossipingAccrualFailureDetectorSpec extends MultiNodeSpec(GossipingAccrualFailureDetectorMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import GossipingAccrualFailureDetectorMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  after {
    testConductor.enter("after")
  }

  "A Gossip-driven Failure Detector" must {

    "receive gossip heartbeats so that all member nodes in the cluster are marked 'available'" taggedAs LongRunningTest in {
      // make sure that the node-to-join is started before other join
      runOn(first) {
        startClusterNode()
      }
      testConductor.enter("first-started")

      cluster.join(firstAddress)

      5.seconds.dilated.sleep // let them gossip
      cluster.failureDetector.isAvailable(firstAddress) must be(true)
      cluster.failureDetector.isAvailable(secondAddress) must be(true)
      cluster.failureDetector.isAvailable(thirdAddress) must be(true)
    }

    "mark node as 'unavailable' if a node in the cluster is shut down (and its heartbeats stops)" taggedAs LongRunningTest in {
      runOn(first) {
        testConductor.shutdown(third, 0)
        testConductor.removeNode(third)
      }

      runOn(first, second) {
        // remaning nodes should detect failure...
        awaitCond(!cluster.failureDetector.isAvailable(thirdAddress), 10.seconds)
        // other connections still ok
        cluster.failureDetector.isAvailable(firstAddress) must be(true)
        cluster.failureDetector.isAvailable(secondAddress) must be(true)
      }
    }
  }

}
