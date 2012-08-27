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
import akka.actor.Address
import akka.actor.Props
import akka.actor.Actor

object MembershipChangeListenerLeavingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
        akka.clusterView.leader-actions-interval           = 5 s
        akka.cluster.unreachable-nodes-reaper-interval = 300 s # turn "off"
      """))
      .withFallback(MultiNodeClusterSpec.clusterConfig))
}

class MembershipChangeListenerLeavingMultiJvmNode1 extends MembershipChangeListenerLeavingSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerLeavingMultiJvmNode2 extends MembershipChangeListenerLeavingSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerLeavingMultiJvmNode3 extends MembershipChangeListenerLeavingSpec with FailureDetectorPuppetStrategy

abstract class MembershipChangeListenerLeavingSpec
  extends MultiNodeSpec(MembershipChangeListenerLeavingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerLeavingMultiJvmSpec._
  import ClusterEvent._

  "A registered MembershipChangeListener" must {
    "be notified when new node is LEAVING" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first) {
        enterBarrier("registered-listener")
        cluster.leave(second)
      }

      runOn(second) {
        enterBarrier("registered-listener")
      }

      runOn(third) {
        val latch = TestLatch()
        cluster.subscribe(system.actorOf(Props(new Actor {
          def receive = {
            case MemberLeft(m) if m.address == address(second) ⇒
              latch.countDown()
            case _ ⇒ // ignore
          }
        })), classOf[MemberEvent])
        enterBarrier("registered-listener")
        latch.await
      }

      enterBarrier("finished")
    }
  }
}
