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

object MembershipChangeListenerUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class MembershipChangeListenerUpMultiJvmNode1 extends MembershipChangeListenerUpSpec with AccrualFailureDetectorStrategy
class MembershipChangeListenerUpMultiJvmNode2 extends MembershipChangeListenerUpSpec with AccrualFailureDetectorStrategy

abstract class MembershipChangeListenerUpSpec
  extends MultiNodeSpec(MembershipChangeListenerUpMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerUpMultiJvmSpec._

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address

  "A registered MembershipChangeListener" must {
    "be notified when new node is marked as UP by the leader" taggedAs LongRunningTest in {

      runOn(first) {
        val upLatch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 2 && members.forall(_.status == MemberStatus.Up))
              upLatch.countDown()
          }
        })
        testConductor.enter("registered-listener")

        upLatch.await
        awaitUpConvergence(numberOfMembers = 2)
      }

      runOn(second) {
        testConductor.enter("registered-listener")
        cluster.join(firstAddress)
      }

      testConductor.enter("after")
    }
  }
}
