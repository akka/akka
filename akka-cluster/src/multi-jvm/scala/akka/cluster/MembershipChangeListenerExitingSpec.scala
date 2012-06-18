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
          leader-actions-interval           = 5 s  # increase the leader action task interval
          unreachable-nodes-reaper-interval = 300 s # turn "off" reaping to unreachable node set
        }
      """)
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class MembershipChangeListenerExitingMultiJvmNode1 extends MembershipChangeListenerExitingSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerExitingMultiJvmNode2 extends MembershipChangeListenerExitingSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerExitingMultiJvmNode3 extends MembershipChangeListenerExitingSpec with FailureDetectorPuppetStrategy

abstract class MembershipChangeListenerExitingSpec
  extends MultiNodeSpec(MembershipChangeListenerExitingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerExitingMultiJvmSpec._

  "A registered MembershipChangeListener" must {
    "be notified when new node is EXITING" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first) {
        testConductor.enter("registered-listener")
        cluster.leave(second)
      }

      runOn(second) {
        testConductor.enter("registered-listener")
      }

      runOn(third) {
        val exitingLatch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 3 && members.exists(m ⇒ m.address == address(second) && m.status == MemberStatus.Exiting))
              exitingLatch.countDown()
          }
        })
        testConductor.enter("registered-listener")
        exitingLatch.await
      }

      testConductor.enter("finished")
    }
  }
}
