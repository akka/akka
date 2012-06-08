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
        akka.cluster.leader-actions-interval           = 5 s
        akka.cluster.unreachable-nodes-reaper-interval = 30 s
      """))
      .withFallback(MultiNodeClusterSpec.clusterConfig))
}

class MembershipChangeListenerLeavingMultiJvmNode1 extends MembershipChangeListenerLeavingSpec
class MembershipChangeListenerLeavingMultiJvmNode2 extends MembershipChangeListenerLeavingSpec
class MembershipChangeListenerLeavingMultiJvmNode3 extends MembershipChangeListenerLeavingSpec

abstract class MembershipChangeListenerLeavingSpec
  extends MultiNodeSpec(MembershipChangeListenerLeavingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerLeavingMultiJvmSpec._

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  "A registered MembershipChangeListener" must {
    "be notified when new node is LEAVING" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first) {
        testConductor.enter("registered-listener")
        cluster.leave(secondAddress)
      }

      runOn(second) {
        testConductor.enter("registered-listener")
      }

      runOn(third) {
        val latch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 3 && members.exists(m ⇒ m.address == secondAddress && m.status == MemberStatus.Leaving))
              latch.countDown()
          }
        })
        testConductor.enter("registered-listener")
        latch.await
      }

      testConductor.enter("finished")
    }
  }
}
