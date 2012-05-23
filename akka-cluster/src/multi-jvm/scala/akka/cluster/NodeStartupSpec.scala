/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory

import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object NodeStartupMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded host:port
    akka.cluster.node-to-join = "akka://MultiNodeSpec@localhost:2601"
    """)))

  nodeConfig(first, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded port
    akka.remote.netty.port=2601
    """))

}

class NodeStartupMultiJvmNode1 extends NodeStartupSpec
class NodeStartupMultiJvmNode2 extends NodeStartupSpec

class NodeStartupSpec extends MultiNodeSpec(NodeStartupMultiJvmSpec) with ImplicitSender {
  import NodeStartupMultiJvmSpec._

  override def initialParticipants = 2

  var firstNode: Cluster = _

  runOn(first) {
    firstNode = Cluster(system)
  }

  "A first cluster node with a 'node-to-join' config set to empty string (singleton cluster)" must {

    "be a singleton cluster when started up" taggedAs LongRunningTest in {
      runOn(first) {
        awaitCond(firstNode.isSingletonCluster)
      }

      testConductor.enter("done")
    }

    "be in 'Joining' phase when started up" taggedAs LongRunningTest in {
      runOn(first) {
        val members = firstNode.latestGossip.members
        members.size must be(1)
        val firstAddress = testConductor.getAddressFor(first).await
        val joiningMember = members find (_.address == firstAddress)
        joiningMember must not be (None)
        joiningMember.get.status must be(MemberStatus.Joining)
      }

      testConductor.enter("done")
    }
  }

  "A second cluster node with a 'node-to-join' config defined" must {
    "join the other node cluster when sending a Join command" taggedAs LongRunningTest in {
      runOn(second) {
        // start cluster on second node, and join
        Cluster(system)
      }

      runOn(first) {
        val secondAddress = testConductor.getAddressFor(second).await
        awaitCond {
          firstNode.latestGossip.members.exists { member ⇒
            member.address == secondAddress && member.status == MemberStatus.Up
          }
        }
        firstNode.latestGossip.members.size must be(2)
      }

      testConductor.enter("done")
    }
  }

}
