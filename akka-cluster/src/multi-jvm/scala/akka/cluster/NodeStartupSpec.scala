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

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster {
      gossip-frequency = 200 ms
      leader-actions-frequency = 200 ms
      periodic-tasks-initial-delay = 300 ms
    }
    """)))

  nodeConfig(first, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded port
    akka.remote.netty.port=2601
    """))

  nodeConfig(second, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded host:port
    akka.cluster.node-to-join = "akka://MultiNodeSpec@localhost:2601"
    """))

}

class NodeStartupMultiJvmNode1 extends NodeStartupSpec {
  override var node: Cluster = _
}
class NodeStartupMultiJvmNode2 extends NodeStartupSpec {
  override var node: Cluster = _
}

abstract class NodeStartupSpec extends MultiNodeSpec(NodeStartupMultiJvmSpec) with ImplicitSender with BeforeAndAfter {
  import NodeStartupMultiJvmSpec._

  override def initialParticipants = 2

  var node: Cluster

  after {
    testConductor.enter("after")
  }

  runOn(first) {
    node = Cluster(system)
  }

  "A first cluster node with a 'node-to-join' config set to empty string (singleton cluster)" must {

    "be a singleton cluster when started up" in {
      runOn(first) {
        awaitCond(node.isSingletonCluster)
        // FIXME #2117 singletonCluster should reach convergence
        //awaitCond(firstNode.convergence.isDefined)
      }
    }

    "be in 'Joining' phase when started up" in {
      runOn(first) {
        val members = node.latestGossip.members
        members.size must be(1)
        val firstAddress = testConductor.getAddressFor(first).await
        val joiningMember = members find (_.address == firstAddress)
        joiningMember must not be (None)
        joiningMember.get.status must be(MemberStatus.Joining)
      }
    }
  }

  "A second cluster node with a 'node-to-join' config defined" must {
    "join the other node cluster when sending a Join command" in {
      val secondAddress = testConductor.getAddressFor(second).await

      def awaitSecondUp = awaitCond {
        node.latestGossip.members.exists { member ⇒
          member.address == secondAddress && member.status == MemberStatus.Up
        }
      }

      runOn(second) {
        // start cluster on second node, and join
        node = Cluster(system)
        awaitSecondUp
        node.convergence.isDefined
      }

      runOn(first) {
        awaitSecondUp
        node.latestGossip.members.size must be(2)
        node.convergence.isDefined
      }
    }
  }

}
