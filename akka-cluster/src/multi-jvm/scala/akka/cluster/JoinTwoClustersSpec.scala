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

  def node(): Cluster = Cluster(system)

  after {
    testConductor.enter("after")
  }

  val a1Address = testConductor.getAddressFor(a1).await
  val b1Address = testConductor.getAddressFor(b1).await
  val c1Address = testConductor.getAddressFor(c1).await

  def awaitUpConvergence(numberOfMembers: Int): Unit = {
    awaitCond(node().latestGossip.members.size == numberOfMembers)
    awaitCond(node().latestGossip.members.forall(_.status == MemberStatus.Up))
    awaitCond(node().convergence.isDefined)
  }

  "Three different clusters (A, B and C)" must {

    "be able to 'elect' a single leader after joining (A -> B)" in {

      runOn(a1, a2) {
        node().join(a1Address)
      }
      runOn(b1, b2) {
        node().join(b1Address)
      }
      runOn(c1, c2) {
        node().join(c1Address)
      }

      awaitUpConvergence(numberOfMembers = 2)

      node().isLeader must be(ifNode(a1, b1, c1)(true)(false))

      runOn(b2) {
        node().join(a1Address)
      }

      runOn(a1, a2, b1, b2) {
        awaitUpConvergence(numberOfMembers = 4)
      }

      node().isLeader must be(ifNode(a1, c1)(true)(false))

    }

    "be able to 'elect' a single leader after joining (C -> A + B)" in {

      runOn(b2) {
        node().join(c1Address)
      }

      awaitUpConvergence(numberOfMembers = 6)

      node().isLeader must be(ifNode(a1)(true)(false))
    }
  }

}
