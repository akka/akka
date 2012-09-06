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
import akka.cluster.MemberStatus._

object MembershipChangeListenerLeavingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
        akka.cluster.unreachable-nodes-reaper-interval = 300 s # turn "off"
      """))
      .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class MembershipChangeListenerLeavingMultiJvmNode1 extends MembershipChangeListenerLeavingSpec
class MembershipChangeListenerLeavingMultiJvmNode2 extends MembershipChangeListenerLeavingSpec
class MembershipChangeListenerLeavingMultiJvmNode3 extends MembershipChangeListenerLeavingSpec

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
        val secondAddress = address(second)
        cluster.subscribe(system.actorOf(Props(new Actor {
          def receive = {
            case state: CurrentClusterState ⇒
              if (state.members.exists(m ⇒ m.address == secondAddress && m.status == Leaving))
                latch.countDown()
            case MemberLeft(m) if m.address == secondAddress ⇒
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
