/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object MembershipChangeListenerMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class MembershipChangeListenerMultiJvmNode1 extends MembershipChangeListenerSpec with AccrualFailureDetectorStrategy
class MembershipChangeListenerMultiJvmNode2 extends MembershipChangeListenerSpec with AccrualFailureDetectorStrategy
class MembershipChangeListenerMultiJvmNode3 extends MembershipChangeListenerSpec with AccrualFailureDetectorStrategy

abstract class MembershipChangeListenerSpec extends MultiNodeSpec(MembershipChangeListenerMultiJvmSpec)
  with MultiNodeClusterSpec {
  import MembershipChangeListenerMultiJvmSpec._

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address

  "A set of connected cluster systems" must {

    "(when two nodes) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {

      awaitClusterUp(first)

      runOn(first, second) {
        val latch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 2 && members.forall(_.status == MemberStatus.Up))
              latch.countDown()
          }
        })
        testConductor.enter("listener-1-registered")
        cluster.join(firstAddress)
        latch.await
      }

      runOn(third) {
        testConductor.enter("listener-1-registered")
      }

      testConductor.enter("after-1")
    }

    "(when three nodes) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {

      val latch = TestLatch()
      cluster.registerListener(new MembershipChangeListener {
        def notify(members: SortedSet[Member]) {
          if (members.size == 3 && members.forall(_.status == MemberStatus.Up))
            latch.countDown()
        }
      })
      testConductor.enter("listener-2-registered")

      runOn(third) {
        cluster.join(firstAddress)
      }

      latch.await

      testConductor.enter("after-2")
    }
  }
}
