/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object NodeJoinAndUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false)
    .withFallback(ConfigFactory.parseString("""
        akka.cluster {
          gossip-frequency = 1000 ms
          leader-actions-frequency = 5000 ms  # increase the leader action task frequency
        }
      """)
    .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class NodeJoinAndUpMultiJvmNode1 extends NodeJoinAndUpSpec
class NodeJoinAndUpMultiJvmNode2 extends NodeJoinAndUpSpec

abstract class NodeJoinAndUpSpec
  extends MultiNodeSpec(NodeJoinAndUpMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender
  with BeforeAndAfter {

  import NodeJoinAndUpMultiJvmSpec._

  override def initialParticipants = 2

  after {
    testConductor.enter("after")
  }

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address

  "A first cluster node with a 'node-to-join' config set to empty string (singleton cluster)" must {

    "be a singleton cluster when started up" taggedAs LongRunningTest in {
      runOn(first) {
        awaitCond(cluster.isSingletonCluster)
        awaitUpConvergence(numberOfMembers = 1)
        cluster.isLeader must be(true)
      }
    }
  }

  "A second cluster node" must {
    "join the cluster as JOINING - when sending a 'Join' command - and then be moved to UP by the leader" taggedAs LongRunningTest in {

      runOn(second) {
        cluster.join(firstAddress)
      }

      awaitCond(cluster.latestGossip.members.exists { member ⇒ member.address == secondAddress && member.status == MemberStatus.Joining })

      awaitCond(
        cluster.latestGossip.members.exists { member ⇒ member.address == secondAddress && member.status == MemberStatus.Up },
        30.seconds.dilated) // waiting for the leader to move from JOINING -> UP (frequency set to 5 sec in config)

      cluster.latestGossip.members.size must be(2)
      awaitCond(cluster.convergence.isDefined)
    }
  }
}
