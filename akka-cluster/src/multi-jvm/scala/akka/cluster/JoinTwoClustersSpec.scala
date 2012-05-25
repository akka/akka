/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.BeforeAndAfter
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender

object JoinTwoClustersMultiJvmSpec extends MultiNodeConfig {
  val a1 = role("a1")
  val a2 = role("a2")
  val b1 = role("b1")
  val b2 = role("b2")
  val c1 = role("c1")
  val c2 = role("c2")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster {
      gossip-frequency = 200 ms
      leader-actions-frequency = 200 ms
      periodic-tasks-initial-delay = 300 ms
    }
    """)))

}

class JoinTwoClustersMultiJvmNode1 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode2 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode3 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode4 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode5 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode6 extends JoinTwoClustersSpec

abstract class JoinTwoClustersSpec extends MultiNodeSpec(JoinTwoClustersMultiJvmSpec) with ImplicitSender with BeforeAndAfter {
  import JoinTwoClustersMultiJvmSpec._

  override def initialParticipants = 6

  def cluster: Cluster = Cluster(system)

  after {
    testConductor.enter("after")
  }

  val a1Address = node(a1).address
  val b1Address = node(b1).address
  val c1Address = node(c1).address

  def awaitUpConvergence(numberOfMembers: Int): Unit = {
    awaitCond(cluster.latestGossip.members.size == numberOfMembers)
    awaitCond(cluster.latestGossip.members.forall(_.status == MemberStatus.Up))
    awaitCond(cluster.convergence.isDefined)
  }

  "Three different clusters (A, B and C)" must {

    "be able to 'elect' a single leader after joining (A -> B)" in {

      runOn(a1, a2) {
        cluster.join(a1Address)
      }
      runOn(b1, b2) {
        cluster.join(b1Address)
      }
      runOn(c1, c2) {
        cluster.join(c1Address)
      }

      awaitUpConvergence(numberOfMembers = 2)

      cluster.isLeader must be(ifNode(a1, b1, c1)(true)(false))

      runOn(b2) {
        cluster.join(a1Address)
      }

      runOn(a1, a2, b1, b2) {
        awaitUpConvergence(numberOfMembers = 4)
      }

      cluster.isLeader must be(ifNode(a1, c1)(true)(false))

    }

    "be able to 'elect' a single leader after joining (C -> A + B)" in {

      runOn(b2) {
        cluster.join(c1Address)
      }

      awaitUpConvergence(numberOfMembers = 6)

      cluster.isLeader must be(ifNode(a1)(true)(false))
    }
  }

}
