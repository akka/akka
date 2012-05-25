/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object NodeStartupMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class NodeStartupMultiJvmNode1 extends NodeStartupSpec
class NodeStartupMultiJvmNode2 extends NodeStartupSpec

abstract class NodeStartupSpec extends MultiNodeSpec(NodeStartupMultiJvmSpec) with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import NodeStartupMultiJvmSpec._

  override def initialParticipants = 2

  after {
    testConductor.enter("after")
  }

  val firstAddress = node(first).address
  val secondAddress = node(second).address

  "A first cluster node with a 'node-to-join' config set to empty string (singleton cluster)" must {

    "be a singleton cluster when started up" in {
      runOn(first) {
        awaitCond(cluster.isSingletonCluster)
        // FIXME #2117 singletonCluster should reach convergence
        //awaitCond(cluster.convergence.isDefined)
      }
    }

    "be in 'Joining' phase when started up" in {
      runOn(first) {
        val members = cluster.latestGossip.members
        members.size must be(1)

        val joiningMember = members find (_.address == firstAddress)
        joiningMember must not be (None)
        joiningMember.get.status must be(MemberStatus.Joining)
      }
    }
  }

  "A second cluster node" must {
    "join the other node cluster when sending a Join command" in {

      runOn(second) {
        cluster.join(firstAddress)
      }

      awaitCond {
        cluster.latestGossip.members.exists { member ⇒
          member.address == secondAddress && member.status == MemberStatus.Up
        }
      }
      cluster.latestGossip.members.size must be(2)
      awaitCond(cluster.convergence.isDefined)
    }
  }

}
