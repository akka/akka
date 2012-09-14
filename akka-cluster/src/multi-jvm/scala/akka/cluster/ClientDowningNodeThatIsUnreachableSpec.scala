/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Address

case class ClientDowningNodeThatIsUnreachableMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig(failureDetectorPuppet)))
}

class ClientDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode1 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)
class ClientDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode2 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)
class ClientDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode3 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)
class ClientDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode4 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)

class ClientDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode1 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)
class ClientDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode2 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)
class ClientDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode3 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)
class ClientDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode4 extends ClientDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)

abstract class ClientDowningNodeThatIsUnreachableSpec(multiNodeConfig: ClientDowningNodeThatIsUnreachableMultiNodeConfig)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(ClientDowningNodeThatIsUnreachableMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  "Client of a 4 node cluster" must {

    "be able to DOWN a node that is UNREACHABLE (killed)" taggedAs LongRunningTest in {
      val thirdAddress = address(third)
      awaitClusterUp(first, second, third, fourth)

      runOn(first) {
        // kill 'third' node
        testConductor.shutdown(third, 0)
        markNodeAsUnavailable(thirdAddress)

        // mark 'third' node as DOWN
        cluster.down(thirdAddress)
        enterBarrier("down-third-node")

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
