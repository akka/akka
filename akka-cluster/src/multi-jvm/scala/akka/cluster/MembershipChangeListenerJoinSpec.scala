/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object MembershipChangeListenerJoinMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("akka.cluster.leader-actions-interval = 5 s") // increase the leader action task interval to allow time checking for JOIN before leader moves it to UP
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class MembershipChangeListenerJoinMultiJvmNode1 extends MembershipChangeListenerJoinSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerJoinMultiJvmNode2 extends MembershipChangeListenerJoinSpec with FailureDetectorPuppetStrategy

abstract class MembershipChangeListenerJoinSpec
  extends MultiNodeSpec(MembershipChangeListenerJoinMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerJoinMultiJvmSpec._

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address

  "A registered MembershipChangeListener" must {
    "be notified when new node is JOINING" taggedAs LongRunningTest in {

      runOn(first) {
        val joinLatch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 2 && members.exists(_.status == MemberStatus.Joining))
              joinLatch.countDown()
          }
        })
        testConductor.enter("registered-listener")

        joinLatch.await
      }

      runOn(second) {
        testConductor.enter("registered-listener")
        cluster.join(firstAddress)
      }

      awaitUpConvergence(2)

      testConductor.enter("after")
    }
  }
}
