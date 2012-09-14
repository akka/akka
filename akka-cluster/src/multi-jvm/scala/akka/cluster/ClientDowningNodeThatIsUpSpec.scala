/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Address

case class ClientDowningNodeThatIsUpMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig(failureDetectorPuppet)))
}

class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode1 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = true)
class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode2 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = true)
class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode3 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = true)
class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode4 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = true)

class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode1 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = false)
class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode2 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = false)
class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode3 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = false)
class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode4 extends ClientDowningNodeThatIsUpSpec(failureDetectorPuppet = false)

abstract class ClientDowningNodeThatIsUpSpec(multiNodeConfig: ClientDowningNodeThatIsUpMultiNodeConfig)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(ClientDowningNodeThatIsUpMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  "Client of a 4 node cluster" must {

    "be able to DOWN a node that is UP (healthy and available)" taggedAs LongRunningTest in {
      val thirdAddress = address(third)
      awaitClusterUp(first, second, third, fourth)

      runOn(first) {
        // mark 'third' node as DOWN
        cluster.down(thirdAddress)
        enterBarrier("down-third-node")

        markNodeAsUnavailable(thirdAddress)

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(thirdAddress))
        clusterView.members.exists(_.address == thirdAddress) must be(false)
      }

      runOn(third) {
        enterBarrier("down-third-node")
      }

      runOn(second, fourth) {
        enterBarrier("down-third-node")

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(thirdAddress))
      }

      enterBarrier("await-completion")
    }
  }
}
