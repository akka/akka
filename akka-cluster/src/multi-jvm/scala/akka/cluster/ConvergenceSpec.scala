/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._
import akka.actor.Address

object ConvergenceMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.failure-detector.threshold = 4")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class ConvergenceWithFailureDetectorPuppetMultiJvmNode1 extends ConvergenceSpec with FailureDetectorPuppetStrategy
class ConvergenceWithFailureDetectorPuppetMultiJvmNode2 extends ConvergenceSpec with FailureDetectorPuppetStrategy
class ConvergenceWithFailureDetectorPuppetMultiJvmNode3 extends ConvergenceSpec with FailureDetectorPuppetStrategy
class ConvergenceWithFailureDetectorPuppetMultiJvmNode4 extends ConvergenceSpec with FailureDetectorPuppetStrategy

class ConvergenceWithAccrualFailureDetectorMultiJvmNode1 extends ConvergenceSpec with AccrualFailureDetectorStrategy
class ConvergenceWithAccrualFailureDetectorMultiJvmNode2 extends ConvergenceSpec with AccrualFailureDetectorStrategy
class ConvergenceWithAccrualFailureDetectorMultiJvmNode3 extends ConvergenceSpec with AccrualFailureDetectorStrategy
class ConvergenceWithAccrualFailureDetectorMultiJvmNode4 extends ConvergenceSpec with AccrualFailureDetectorStrategy

abstract class ConvergenceSpec
  extends MultiNodeSpec(ConvergenceMultiJvmSpec)
  with MultiNodeClusterSpec {

  import ConvergenceMultiJvmSpec._

  "A cluster of 3 members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)

      runOn(fourth) {
        // doesn't join immediately
      }

      enterBarrier("after-1")
    }

    "not reach convergence while any nodes are unreachable" taggedAs LongRunningTest in {
      val thirdAddress = address(third)
      enterBarrier("before-shutdown")

      runOn(first) {
        // kill 'third' node
        testConductor.shutdown(third, 0)
        markNodeAsUnavailable(thirdAddress)
      }

      runOn(first, second) {

        within(28 seconds) {
          // third becomes unreachable
          awaitCond(cluster.latestGossip.overview.unreachable.size == 1)
          awaitCond(cluster.latestGossip.members.size == 2)
          awaitCond(cluster.latestGossip.members.forall(_.status == MemberStatus.Up))
          awaitSeenSameState(first, second)
          // still one unreachable
          cluster.latestGossip.overview.unreachable.size must be(1)
          cluster.latestGossip.overview.unreachable.head.address must be(thirdAddress)
          // and therefore no convergence
          cluster.convergence.isDefined must be(false)

        }
      }

      enterBarrier("after-2")
    }

    "not move a new joining node to Up while there is no convergence" taggedAs LongRunningTest in {
      runOn(fourth) {
        // try to join
        cluster.join(first)
      }

      def memberStatus(address: Address): Option[MemberStatus] =
        cluster.latestGossip.members.collectFirst { case m if m.address == address ⇒ m.status }

      def assertNotMovedUp: Unit = {
        within(20 seconds) {
          awaitCond(cluster.latestGossip.members.size == 3)
          awaitSeenSameState(first, second, fourth)
          memberStatus(first) must be(Some(MemberStatus.Up))
          memberStatus(second) must be(Some(MemberStatus.Up))
          // leader is not allowed to move the new node to Up
          memberStatus(fourth) must be(Some(MemberStatus.Joining))
          // still no convergence
          cluster.convergence.isDefined must be(false)
        }
      }

      runOn(first, second, fourth) {
        for (n ← 1 to 5) {
          log.debug("assertNotMovedUp#" + n)
          assertNotMovedUp
          // wait and then check again
          Thread.sleep(1.second.dilated.toMillis)
        }
      }

      enterBarrier("after-3")
    }
  }
}
