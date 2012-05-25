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

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster {
      gossip-frequency = 200 ms
      leader-actions-frequency = 200 ms
      periodic-tasks-initial-delay = 300 ms
    }
    """)))

}

class NodeMembershipMultiJvmNode1 extends NodeMembershipSpec
class NodeMembershipMultiJvmNode2 extends NodeMembershipSpec
class NodeMembershipMultiJvmNode3 extends NodeMembershipSpec

abstract class NodeMembershipSpec extends MultiNodeSpec(NodeMembershipMultiJvmSpec) with ImplicitSender with BeforeAndAfter {
  import NodeMembershipMultiJvmSpec._

  override def initialParticipants = 3

  def cluster: Cluster = Cluster(system)

  after {
    testConductor.enter("after")
  }

  val firstAddress = node(first).address
  val secondAddress = node(second).address
  val thirdAddress = node(third).address

  "A set of connected cluster systems" must {

    "(when two systems) start gossiping to each other so that both systems gets the same gossip info" in {

      runOn(first, second) {
        cluster.join(firstAddress)
        awaitCond(cluster.latestGossip.members.size == 2)
        val members = cluster.latestGossip.members.toIndexedSeq
        members.size must be(2)
        val sortedAddresses = IndexedSeq(firstAddress, secondAddress).sortBy(_.toString)
        members(0).address must be(sortedAddresses(0))
        members(1).address must be(sortedAddresses(1))
        awaitCond {
          cluster.latestGossip.members.forall(_.status == MemberStatus.Up)
        }
        awaitCond(cluster.convergence.isDefined)
      }

    }

    "(when three systems) start gossiping to each other so that both systems gets the same gossip info" in {

      runOn(third) {
        cluster.join(firstAddress)
      }

      // runOn all
      awaitCond(cluster.latestGossip.members.size == 3)
      val members = cluster.latestGossip.members.toIndexedSeq
      members.size must be(3)
      val sortedAddresses = IndexedSeq(firstAddress, secondAddress, thirdAddress).sortBy(_.toString)
      members(0).address must be(sortedAddresses(0))
      members(1).address must be(sortedAddresses(1))
      members(2).address must be(sortedAddresses(2))
      awaitCond {
        cluster.latestGossip.members.forall(_.status == MemberStatus.Up)
      }
      awaitCond(cluster.convergence.isDefined)

    }
  }

}
