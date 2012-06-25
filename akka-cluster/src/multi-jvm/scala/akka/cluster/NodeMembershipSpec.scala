/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object NodeMembershipMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class NodeMembershipMultiJvmNode1 extends NodeMembershipSpec with FailureDetectorPuppetStrategy
class NodeMembershipMultiJvmNode2 extends NodeMembershipSpec with FailureDetectorPuppetStrategy
class NodeMembershipMultiJvmNode3 extends NodeMembershipSpec with FailureDetectorPuppetStrategy

abstract class NodeMembershipSpec
  extends MultiNodeSpec(NodeMembershipMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeMembershipMultiJvmSpec._

  "A set of connected cluster systems" must {

    "(when two nodes) start gossiping to each other so that both nodes gets the same gossip info" taggedAs LongRunningTest in {

      // make sure that the node-to-join is started before other join
      runOn(first) {
        startClusterNode()
      }
      enterBarrier("first-started")

      runOn(first, second) {
        cluster.join(first)
        awaitCond(cluster.latestGossip.members.size == 2)
        assertMembers(cluster.latestGossip.members, first, second)
        awaitCond {
          cluster.latestGossip.members.forall(_.status == MemberStatus.Up)
        }
        awaitCond(cluster.convergence.isDefined)
      }

      enterBarrier("after-1")
    }

    "(when three nodes) start gossiping to each other so that all nodes gets the same gossip info" taggedAs LongRunningTest in {

      runOn(third) {
        cluster.join(first)
      }

      awaitCond(cluster.latestGossip.members.size == 3)
      assertMembers(cluster.latestGossip.members, first, second, third)
      awaitCond {
        cluster.latestGossip.members.forall(_.status == MemberStatus.Up)
      }
      awaitCond(cluster.convergence.isDefined)

      enterBarrier("after-2")
    }
  }
}
