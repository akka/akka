/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.duration._

case class SingletonClusterMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster {
        auto-join                  = on
        auto-down                  = on
        failure-detector.threshold = 4
      }
    """)).
    withFallback(MultiNodeClusterSpec.clusterConfig(failureDetectorPuppet)))

}

class SingletonClusterWithFailureDetectorPuppetMultiJvmNode1 extends SingletonClusterSpec(failureDetectorPuppet = true)
class SingletonClusterWithFailureDetectorPuppetMultiJvmNode2 extends SingletonClusterSpec(failureDetectorPuppet = true)

class SingletonClusterWithAccrualFailureDetectorMultiJvmNode1 extends SingletonClusterSpec(failureDetectorPuppet = false)
class SingletonClusterWithAccrualFailureDetectorMultiJvmNode2 extends SingletonClusterSpec(failureDetectorPuppet = false)

abstract class SingletonClusterSpec(multiNodeConfig: SingletonClusterMultiNodeConfig)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(SingletonClusterMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  muteMarkingAsUnreachable()

  "A cluster of 2 nodes" must {

    "become singleton cluster when started with 'auto-join=on' and 'seed-nodes=[]'" taggedAs LongRunningTest in {
      awaitUpConvergence(1)
      clusterView.isSingletonCluster must be(true)

      enterBarrier("after-1")
    }

    "not be singleton cluster when joined with other node" taggedAs LongRunningTest in {
      awaitClusterUp(first, second)
      clusterView.isSingletonCluster must be(false)
      assertLeader(first, second)

      enterBarrier("after-2")
    }

    "become singleton cluster when one node is shutdown" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = address(second)
        testConductor.shutdown(second, 0).await

        markNodeAsUnavailable(secondAddress)

        awaitUpConvergence(numberOfMembers = 1, canNotBePartOfMemberRing = Seq(secondAddress), 30.seconds)
        clusterView.isSingletonCluster must be(true)
        awaitCond(clusterView.isLeader)
      }

      enterBarrier("after-3")
    }
  }
}
