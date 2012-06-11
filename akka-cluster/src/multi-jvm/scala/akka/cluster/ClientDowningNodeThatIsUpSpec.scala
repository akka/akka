/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Address

object ClientDowningNodeThatIsUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode1 extends ClientDowningNodeThatIsUpSpec with FailureDetectorPuppetStrategy
class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode2 extends ClientDowningNodeThatIsUpSpec with FailureDetectorPuppetStrategy
class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode3 extends ClientDowningNodeThatIsUpSpec with FailureDetectorPuppetStrategy
class ClientDowningNodeThatIsUpWithFailureDetectorPuppetMultiJvmNode4 extends ClientDowningNodeThatIsUpSpec with FailureDetectorPuppetStrategy

class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode1 extends ClientDowningNodeThatIsUpSpec with AccrualFailureDetectorStrategy
class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode2 extends ClientDowningNodeThatIsUpSpec with AccrualFailureDetectorStrategy
class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode3 extends ClientDowningNodeThatIsUpSpec with AccrualFailureDetectorStrategy
class ClientDowningNodeThatIsUpWithAccrualFailureDetectorMultiJvmNode4 extends ClientDowningNodeThatIsUpSpec with AccrualFailureDetectorStrategy

abstract class ClientDowningNodeThatIsUpSpec
  extends MultiNodeSpec(ClientDowningNodeThatIsUpMultiJvmSpec)
  with MultiNodeClusterSpec {

  import ClientDowningNodeThatIsUpMultiJvmSpec._

  "Client of a 4 node cluster" must {

    "be able to DOWN a node that is UP (healthy and available)" taggedAs LongRunningTest in {
      val thirdAddress = node(third).address
      awaitClusterUp(first, second, third, fourth)

      runOn(first) {
        // mark 'third' node as DOWN
        cluster.down(thirdAddress)
        testConductor.enter("down-third-node")

        markNodeAsUnavailable(thirdAddress)

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(thirdAddress))
        cluster.latestGossip.members.exists(_.address == thirdAddress) must be(false)
      }

      runOn(third) {
        testConductor.enter("down-third-node")
      }

      runOn(second, fourth) {
        testConductor.enter("down-third-node")

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(thirdAddress))
      }

      testConductor.enter("await-completion")
    }
  }
}
