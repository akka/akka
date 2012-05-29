/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object NodeMembershipMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class NodeMembershipMultiJvmNode1 extends NodeMembershipSpec
class NodeMembershipMultiJvmNode2 extends NodeMembershipSpec
class NodeMembershipMultiJvmNode3 extends NodeMembershipSpec

abstract class NodeMembershipSpec extends MultiNodeSpec(NodeMembershipMultiJvmSpec) with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import NodeMembershipMultiJvmSpec._

  override def initialParticipants = 3

  after {
    testConductor.enter("after")
  }

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  "A set of connected cluster systems" must {

    "(when two systems) start gossiping to each other so that both systems gets the same gossip info" taggedAs LongRunningTest in {

      // make sure that the node-to-join is started before other join
      runOn(first) {
        cluster.self
      }
      testConductor.enter("first-started")

      runOn(first, second) {
        cluster.join(firstAddress)
        awaitCond(cluster.latestGossip.members.size == 2)
        assertMembers(cluster.latestGossip.members, firstAddress, secondAddress)
        awaitCond {
          cluster.latestGossip.members.forall(_.status == MemberStatus.Up)
        }
        awaitCond(cluster.convergence.isDefined)
      }

    }

    "(when three systems) start gossiping to each other so that both systems gets the same gossip info" taggedAs LongRunningTest in {

      runOn(third) {
        cluster.join(firstAddress)
      }

      awaitCond(cluster.latestGossip.members.size == 3)
      assertMembers(cluster.latestGossip.members, firstAddress, secondAddress, thirdAddress)
      awaitCond {
        cluster.latestGossip.members.forall(_.status == MemberStatus.Up)
      }
      awaitCond(cluster.convergence.isDefined)

    }
  }

}
