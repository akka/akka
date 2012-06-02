/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import org.scalatest.BeforeAndAfter
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object MembershipChangeListenerExitingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
    .withFallback(ConfigFactory.parseString("""
        akka.cluster {
          leader-actions-frequency           = 5000 ms  # increase the leader action task frequency
          unreachable-nodes-reaper-frequency = 30000 ms # turn "off" reaping to unreachable node set
        }
      """)
    .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class MembershipChangeListenerExitingMultiJvmNode1 extends MembershipChangeListenerExitingSpec
class MembershipChangeListenerExitingMultiJvmNode2 extends MembershipChangeListenerExitingSpec
class MembershipChangeListenerExitingMultiJvmNode3 extends MembershipChangeListenerExitingSpec

abstract class MembershipChangeListenerExitingSpec extends MultiNodeSpec(MembershipChangeListenerExitingMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import MembershipChangeListenerExitingMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  "A registered MembershipChangeListener" must {
    "be notified when new node is EXITING" taggedAs LongRunningTest in {

      runOn(first) {
        cluster.self
      }
      testConductor.enter("first-started")

      runOn(second, third) {
        cluster.join(firstAddress)
      }
      awaitUpConvergence(numberOfMembers = 3)
      testConductor.enter("rest-started")

      runOn(third) {
        val exitingLatch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 3 && members.exists(_.status == MemberStatus.Exiting))
              exitingLatch.countDown()
          }
        })
        exitingLatch.await
      }

      runOn(first) {
        cluster.leave(secondAddress)
      }

      testConductor.enter("finished")
    }
  }
}
