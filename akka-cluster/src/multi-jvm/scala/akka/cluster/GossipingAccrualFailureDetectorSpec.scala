/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

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
    withFallback(ConfigFactory.parseString("akka.cluster.failure-detector.threshold = 4")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class GossipingWithAccrualFailureDetectorMultiJvmNode1 extends GossipingAccrualFailureDetectorSpec with AccrualFailureDetectorStrategy
class GossipingWithAccrualFailureDetectorMultiJvmNode2 extends GossipingAccrualFailureDetectorSpec with AccrualFailureDetectorStrategy
class GossipingWithAccrualFailureDetectorMultiJvmNode3 extends GossipingAccrualFailureDetectorSpec with AccrualFailureDetectorStrategy

abstract class GossipingAccrualFailureDetectorSpec
  extends MultiNodeSpec(GossipingAccrualFailureDetectorMultiJvmSpec)
  with MultiNodeClusterSpec {

  import GossipingAccrualFailureDetectorMultiJvmSpec._

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  "A Gossip-driven Failure Detector" must {

    "receive gossip heartbeats so that all member nodes in the cluster are marked 'available'" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)

      5.seconds.dilated.sleep // let them gossip
      cluster.failureDetector.isAvailable(firstAddress) must be(true)
      cluster.failureDetector.isAvailable(secondAddress) must be(true)
      cluster.failureDetector.isAvailable(thirdAddress) must be(true)

      testConductor.enter("after-1")
    }

    "mark node as 'unavailable' if a node in the cluster is shut down (and its heartbeats stops)" taggedAs LongRunningTest in {
      runOn(first) {
        testConductor.shutdown(third, 0)
      }

      runOn(first, second) {
        // remaning nodes should detect failure...
        awaitCond(!cluster.failureDetector.isAvailable(thirdAddress), 10.seconds)
        // other connections still ok
        cluster.failureDetector.isAvailable(firstAddress) must be(true)
        cluster.failureDetector.isAvailable(secondAddress) must be(true)
      }

      testConductor.enter("after-2")
    }
  }
}
