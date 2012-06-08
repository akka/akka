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

object MembershipChangeListenerRemovedMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class MembershipChangeListenerRemovedMultiJvmNode1 extends MembershipChangeListenerRemovedSpec
class MembershipChangeListenerRemovedMultiJvmNode2 extends MembershipChangeListenerRemovedSpec
class MembershipChangeListenerRemovedMultiJvmNode3 extends MembershipChangeListenerRemovedSpec

abstract class MembershipChangeListenerRemovedSpec extends MultiNodeSpec(MembershipChangeListenerRemovedMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender with BeforeAndAfter {
  import MembershipChangeListenerRemovedMultiJvmSpec._

  override def initialParticipants = 3

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  val reaperWaitingTime = 30.seconds.dilated

  "A registered MembershipChangeListener" must {
    "be notified when new node is REMOVED" taggedAs LongRunningTest in {

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
        val removedLatch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            println("------- MembershipChangeListener " + members.mkString(", "))
            if (members.size == 3 && members.find(_.address == secondAddress).isEmpty)
              removedLatch.countDown()
          }
        })
        removedLatch.await
      }

      runOn(first) {
        cluster.leave(secondAddress)
      }

      testConductor.enter("finished")
    }
  }
}
