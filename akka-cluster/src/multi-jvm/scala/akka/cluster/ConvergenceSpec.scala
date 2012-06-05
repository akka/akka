/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._
import akka.actor.Address

object ConvergenceMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster {
        failure-detector.threshold = 4
      }
    """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class ConvergenceMultiJvmNode1 extends ConvergenceSpec
class ConvergenceMultiJvmNode2 extends ConvergenceSpec
class ConvergenceMultiJvmNode3 extends ConvergenceSpec
class ConvergenceMultiJvmNode4 extends ConvergenceSpec

abstract class ConvergenceSpec
  extends MultiNodeSpec(ConvergenceMultiJvmSpec)
  with MultiNodeClusterSpec {
  import ConvergenceMultiJvmSpec._

  override def initialParticipants = 4

  "A cluster of 3 members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)

      runOn(fourth) {
        // doesn't join immediately
      }

      testConductor.enter("after-1")
    }

    "not reach convergence while any nodes are unreachable" taggedAs LongRunningTest in {
      val thirdAddress = node(third).address
      testConductor.enter("before-shutdown")

      runOn(first) {
        // kill 'third' node
        testConductor.shutdown(third, 0)
      }

      runOn(first, second) {
        val firstAddress = node(first).address
        val secondAddress = node(second).address

        within(28 seconds) {
          // third becomes unreachable
          awaitCond(cluster.latestGossip.overview.unreachable.size == 1)
          awaitCond(cluster.latestGossip.members.size == 2)
          awaitCond(cluster.latestGossip.members.forall(_.status == MemberStatus.Up))
          awaitSeenSameState(Seq(firstAddress, secondAddress))
          // still one unreachable
          cluster.latestGossip.overview.unreachable.size must be(1)
          cluster.latestGossip.overview.unreachable.head.address must be(thirdAddress)
          // and therefore no convergence
          cluster.convergence.isDefined must be(false)

        }
      }

      testConductor.enter("after-2")
    }

    "not move a new joining node to Up while there is no convergence" taggedAs LongRunningTest in {
      runOn(fourth) {
        // try to join
        cluster.join(node(first).address)
      }

      val firstAddress = node(first).address
      val secondAddress = node(second).address
      val fourthAddress = node(fourth).address

      def memberStatus(address: Address): Option[MemberStatus] =
        cluster.latestGossip.members.collectFirst { case m if m.address == address ⇒ m.status }

      def assertNotMovedUp: Unit = {
        within(20 seconds) {
          awaitCond(cluster.latestGossip.members.size == 3)
          awaitSeenSameState(Seq(firstAddress, secondAddress, fourthAddress))
          memberStatus(firstAddress) must be(Some(MemberStatus.Up))
          memberStatus(secondAddress) must be(Some(MemberStatus.Up))
          // leader is not allowed to move the new node to Up
          memberStatus(fourthAddress) must be(Some(MemberStatus.Joining))
          // still no convergence
          cluster.convergence.isDefined must be(false)
        }
      }

      runOn(first, second, fourth) {
        for (n ← 1 to 5) {
          log.debug("assertNotMovedUp#" + n)
          assertNotMovedUp
          // wait and then check again
          1.second.dilated.sleep
        }
      }

      testConductor.enter("after-3")
    }
  }
}
