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

class MembershipChangeListenerMultiJvmNode1 extends MembershipChangeListenerSpec
class MembershipChangeListenerMultiJvmNode2 extends MembershipChangeListenerSpec
class MembershipChangeListenerMultiJvmNode3 extends MembershipChangeListenerSpec

abstract class MembershipChangeListenerSpec extends MultiNodeSpec(MembershipChangeListenerMultiJvmSpec)
  with MultiNodeClusterSpec {
  import MembershipChangeListenerMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address

  "A set of connected cluster systems" must {

    "(when two systems) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {

      // make sure that the node-to-join is started before other join
      runOn(first) {
        cluster.self
      }
      testConductor.enter("first-started")

      runOn(first, second) {
        cluster.join(firstAddress)
        val latch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 2 && members.forall(_.status == MemberStatus.Up))
              latch.countDown()
          }
        })
        latch.await
        cluster.convergence.isDefined must be(true)
      }

      testConductor.enter("after-1")
    }

    "(when three systems) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {

      runOn(third) {
        cluster.join(firstAddress)
      }

      val latch = TestLatch()
      cluster.registerListener(new MembershipChangeListener {
        def notify(members: SortedSet[Member]) {
          if (members.size == 3 && members.forall(_.status == MemberStatus.Up))
            latch.countDown()
        }
      })
      latch.await
      cluster.convergence.isDefined must be(true)

      testConductor.enter("after-2")
    }
  }
}
