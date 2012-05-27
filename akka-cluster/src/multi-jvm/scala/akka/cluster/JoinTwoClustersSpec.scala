/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.BeforeAndAfter
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object JoinTwoClustersMultiJvmSpec extends MultiNodeConfig {
  val a1 = role("a1")
  val a2 = role("a2")
  val b1 = role("b1")
  val b2 = role("b2")
  val c1 = role("c1")
  val c2 = role("c2")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class JoinTwoClustersMultiJvmNode1 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode2 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode3 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode4 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode5 extends JoinTwoClustersSpec
class JoinTwoClustersMultiJvmNode6 extends JoinTwoClustersSpec

abstract class JoinTwoClustersSpec extends MultiNodeSpec(JoinTwoClustersMultiJvmSpec) with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import JoinTwoClustersMultiJvmSpec._

  override def initialParticipants = 6

  after {
    testConductor.enter("after")
  }

  lazy val a1Address = node(a1).address
  lazy val b1Address = node(b1).address
  lazy val c1Address = node(c1).address

  "Three different clusters (A, B and C)" must {

    "be able to 'elect' a single leader after joining (A -> B)" taggedAs LongRunningTest in {
      // make sure that the node-to-join is started before other join
      runOn(a1, b1, c1) {
        cluster
      }
      testConductor.enter("first-started")

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

      assertLeader(a1, a2)
      assertLeader(b1, b2)
      assertLeader(c1, c2)

      runOn(b2) {
        cluster.join(a1Address)
      }

      runOn(a1, a2, b1, b2) {
        awaitUpConvergence(numberOfMembers = 4)
      }

      assertLeader(a1, a2, b1, b2)
      assertLeader(c1, c2)

    }

    "be able to 'elect' a single leader after joining (C -> A + B)" taggedAs LongRunningTest in {

      runOn(b2) {
        cluster.join(c1Address)
      }

      awaitUpConvergence(numberOfMembers = 6)

      assertLeader(a1, a2, b1, b2, c1, c2)
    }
  }

}
