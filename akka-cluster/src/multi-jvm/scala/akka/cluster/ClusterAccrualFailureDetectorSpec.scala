/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import scala.concurrent.util.duration._
import akka.testkit._

object ClusterAccrualFailureDetectorMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.failure-detector.threshold = 4")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class ClusterAccrualFailureDetectorMultiJvmNode1 extends ClusterAccrualFailureDetectorSpec
class ClusterAccrualFailureDetectorMultiJvmNode2 extends ClusterAccrualFailureDetectorSpec
class ClusterAccrualFailureDetectorMultiJvmNode3 extends ClusterAccrualFailureDetectorSpec

abstract class ClusterAccrualFailureDetectorSpec
  extends MultiNodeSpec(ClusterAccrualFailureDetectorMultiJvmSpec)
  with MultiNodeClusterSpec {

  import ClusterAccrualFailureDetectorMultiJvmSpec._

  muteMarkingAsUnreachable()

  "A heartbeat driven Failure Detector" must {

    "receive heartbeats so that all member nodes in the cluster are marked 'available'" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)

      Thread.sleep(5.seconds.dilated.toMillis) // let them heartbeat
      cluster.failureDetector.isAvailable(first) must be(true)
      cluster.failureDetector.isAvailable(second) must be(true)
      cluster.failureDetector.isAvailable(third) must be(true)

      enterBarrier("after-1")
    }

    "mark node as 'unavailable' if a node in the cluster is shut down (and its heartbeats stops)" taggedAs LongRunningTest in {
      runOn(first) {
        testConductor.shutdown(third, 0)
      }

      enterBarrier("third-shutdown")

      runOn(first, second) {
        // remaning nodes should detect failure...
        awaitCond(!cluster.failureDetector.isAvailable(third), 15.seconds)
        // other connections still ok
        cluster.failureDetector.isAvailable(first) must be(true)
        cluster.failureDetector.isAvailable(second) must be(true)
      }

      enterBarrier("after-2")
    }
  }
}
