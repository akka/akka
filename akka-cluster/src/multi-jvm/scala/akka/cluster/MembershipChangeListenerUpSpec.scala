/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object MembershipChangeListenerUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class MembershipChangeListenerUpMultiJvmNode1 extends MembershipChangeListenerUpSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerUpMultiJvmNode2 extends MembershipChangeListenerUpSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerUpMultiJvmNode3 extends MembershipChangeListenerUpSpec with FailureDetectorPuppetStrategy

abstract class MembershipChangeListenerUpSpec
  extends MultiNodeSpec(MembershipChangeListenerUpMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerUpMultiJvmSpec._

  "A set of connected cluster systems" must {

    "(when two nodes) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {

      awaitClusterUp(first)

      runOn(first, second) {
        val latch = TestLatch()
        val expectedAddresses = Set(first, second) map address
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.map(_.address) == expectedAddresses && members.forall(_.status == MemberStatus.Up))
              latch.countDown()
          }
        })
        testConductor.enter("listener-1-registered")
        cluster.join(first)
        latch.await
      }

      runOn(third) {
        testConductor.enter("listener-1-registered")
      }

      testConductor.enter("after-1")
    }

    "(when three nodes) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {

      val latch = TestLatch()
      val expectedAddresses = Set(first, second, third) map address
      cluster.registerListener(new MembershipChangeListener {
        def notify(members: SortedSet[Member]) {
          if (members.map(_.address) == expectedAddresses && members.forall(_.status == MemberStatus.Up))
            latch.countDown()
        }
      })
      testConductor.enter("listener-2-registered")

      runOn(third) {
        cluster.join(first)
      }

      latch.await

      testConductor.enter("after-2")
    }
  }
}
