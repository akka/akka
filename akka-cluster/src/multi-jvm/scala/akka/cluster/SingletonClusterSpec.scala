/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object SingletonClusterMultiJvmSpec extends MultiNodeConfig {
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

class SingletonClusterWithFailureDetectorPuppetMultiJvmNode1 extends SingletonClusterSpec with FailureDetectorPuppetStrategy
class SingletonClusterWithFailureDetectorPuppetMultiJvmNode2 extends SingletonClusterSpec with FailureDetectorPuppetStrategy

class SingletonClusterWithAccrualFailureDetectorMultiJvmNode1 extends SingletonClusterSpec with AccrualFailureDetectorStrategy
class SingletonClusterWithAccrualFailureDetectorMultiJvmNode2 extends SingletonClusterSpec with AccrualFailureDetectorStrategy

abstract class SingletonClusterSpec
  extends MultiNodeSpec(SingletonClusterMultiJvmSpec)
  with MultiNodeClusterSpec {

  import SingletonClusterMultiJvmSpec._

  "A cluster of 2 nodes" must {

    "not be singleton cluster when joined" taggedAs LongRunningTest in {
      awaitClusterUp(first, second)
      cluster.isSingletonCluster must be(false)
      assertLeader(first, second)

      enterBarrier("after-1")
    }

    "become singleton cluster when one node is shutdown" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = address(second)
        testConductor.shutdown(second, 0)

        markNodeAsUnavailable(secondAddress)

        awaitUpConvergence(numberOfMembers = 1, canNotBePartOfMemberRing = Seq(secondAddress), 30.seconds)
        cluster.isSingletonCluster must be(true)
        assertLeader(first)
      }

      enterBarrier("after-2")
    }
  }
}
