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

object MembershipChangeListenerJoinAndUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false)
    .withFallback(ConfigFactory.parseString("""
        akka.cluster {
          gossip-frequency = 1000 ms
          leader-actions-frequency = 5000 ms  # increase the leader action task frequency
        }
      """)
    .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class MembershipChangeListenerJoinAndUpMultiJvmNode1 extends MembershipChangeListenerJoinAndUpSpec
class MembershipChangeListenerJoinAndUpMultiJvmNode2 extends MembershipChangeListenerJoinAndUpSpec

abstract class MembershipChangeListenerJoinAndUpSpec
  extends MultiNodeSpec(MembershipChangeListenerJoinAndUpMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender
  with BeforeAndAfter {

  import MembershipChangeListenerJoinAndUpMultiJvmSpec._

  override def initialParticipants = 2

  after {
    testConductor.enter("after")
  }

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address

  "A registered MembershipChangeListener" must {
    "be notified when new node is JOINING and node is marked as UP by the leader" taggedAs LongRunningTest in {

      runOn(first) {
        cluster.self
      }

      runOn(second) {
        cluster.join(firstAddress)
      }

      runOn(first) {
        // JOINING
        val joinLatch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 2 && members.exists(_.status == MemberStatus.Joining)) // second node is not part of node ring anymore
              joinLatch.countDown()
          }
        })
        joinLatch.await
        cluster.convergence.isDefined must be(true)

        // UP
        val upLatch = TestLatch()
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 2 && members.forall(_.status == MemberStatus.Up))
              upLatch.countDown()
          }
        })
        upLatch.await
        awaitCond(cluster.convergence.isDefined)
      }
    }
  }
}
