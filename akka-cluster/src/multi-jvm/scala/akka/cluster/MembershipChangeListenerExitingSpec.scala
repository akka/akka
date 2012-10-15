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
import scala.concurrent.duration._
import akka.actor.Props
import akka.actor.Actor
import akka.cluster.MemberStatus._

object MembershipChangeListenerExitingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
        akka.cluster {
          unreachable-nodes-reaper-interval = 300 s # turn "off" reaping to unreachable node set
        }
      """)
        .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet)))
}

class MembershipChangeListenerExitingMultiJvmNode1 extends MembershipChangeListenerExitingSpec
class MembershipChangeListenerExitingMultiJvmNode2 extends MembershipChangeListenerExitingSpec
class MembershipChangeListenerExitingMultiJvmNode3 extends MembershipChangeListenerExitingSpec

abstract class MembershipChangeListenerExitingSpec
  extends MultiNodeSpec(MembershipChangeListenerExitingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerExitingMultiJvmSpec._
  import ClusterEvent._

  "A registered MembershipChangeListener" must {
    "be notified when new node is EXITING" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first) {
        enterBarrier("registered-listener")
        cluster.leave(second)
      }

      runOn(second) {
        enterBarrier("registered-listener")
      }

      runOn(third) {
        val exitingLatch = TestLatch()
        val secondAddress = address(second)
        cluster.subscribe(system.actorOf(Props(new Actor {
          def receive = {
            case state: CurrentClusterState ⇒
              if (state.members.exists(m ⇒ m.address == secondAddress && m.status == Exiting))
                exitingLatch.countDown()
            case MemberExited(m) if m.address == secondAddress ⇒
              exitingLatch.countDown()
            case _ ⇒ // ignore
          }
        })), classOf[MemberEvent])
        enterBarrier("registered-listener")
        exitingLatch.await
      }

      enterBarrier("finished")
    }
  }
}
