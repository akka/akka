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

  commonConfig(debugConfig(on = false))

  nodeConfig(first, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded port
    akka.remote.netty.port=2601
    """))

  nodeConfig(second, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded host:port
    akka.cluster.node-to-join = "akka://MultiNodeSpec@localhost:2601"
    """))

}

class NodeStartupMultiJvmNode1 extends NodeStartupSpec
class NodeStartupMultiJvmNode2 extends NodeStartupSpec

class NodeStartupSpec extends MultiNodeSpec(NodeStartupMultiJvmSpec) with ImplicitSender with BeforeAndAfter {
  import NodeStartupMultiJvmSpec._

  override def initialParticipants = 2

  var firstNode: Cluster = _

  after {
    testConductor.enter("after")
  }

  runOn(first) {
    firstNode = Cluster(system)
  }

  "A first cluster node with a 'node-to-join' config set to empty string (singleton cluster)" must {

    "be a singleton cluster when started up" in {
      runOn(first) {
        awaitCond(firstNode.isSingletonCluster)
        firstNode.convergence must be(None)
      }
    }

    "be in 'Joining' phase when started up" in {
      runOn(first) {
        val members = firstNode.latestGossip.members
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
      runOn(second) {
        // start cluster on second node, and join
        val secondNode = Cluster(system)
        awaitCond(secondNode.convergence.isDefined)
      }

      runOn(first) {
        val secondAddress = testConductor.getAddressFor(second).await
        awaitCond {
          firstNode.latestGossip.members.exists { member ⇒
            member.address == secondAddress && member.status == MemberStatus.Up
          }
        }
        firstNode.latestGossip.members.size must be(2)
        awaitCond(firstNode.convergence.isDefined)
      }
    }
  }

}
