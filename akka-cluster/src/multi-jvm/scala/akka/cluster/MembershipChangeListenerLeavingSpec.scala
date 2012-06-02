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

object MembershipChangeListenerLeavingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
    .withFallback(ConfigFactory.parseString("""
        akka.cluster.leader-actions-frequency           = 5000 ms
        akka.cluster.unreachable-nodes-reaper-frequency = 30000 ms # turn "off" reaping to unreachable node set
      """))
    .withFallback(MultiNodeClusterSpec.clusterConfig))
}

class MembershipChangeListenerLeavingMultiJvmNode1 extends MembershipChangeListenerLeavingSpec
class MembershipChangeListenerLeavingMultiJvmNode2 extends MembershipChangeListenerLeavingSpec
class MembershipChangeListenerLeavingMultiJvmNode3 extends MembershipChangeListenerLeavingSpec

abstract class MembershipChangeListenerLeavingSpec extends MultiNodeSpec(MembershipChangeListenerLeavingMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import MembershipChangeListenerLeavingMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  "A registered MembershipChangeListener" must {
    "be notified when new node is LEAVING" taggedAs LongRunningTest in {

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
        val latch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 3 && members.exists(_.status == MemberStatus.Leaving))
              latch.countDown()
          }
        })
        latch.await
      }

      runOn(first) {
        cluster.leave(secondAddress)
      }

      testConductor.enter("finished")
    }
  }
}
