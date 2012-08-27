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
import scala.concurrent.util.duration._
import akka.actor.Props
import akka.actor.Actor

object MembershipChangeListenerJoinMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("akka.clusterView.leader-actions-interval = 5 s") // increase the leader action task interval to allow time checking for JOIN before leader moves it to UP
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class MembershipChangeListenerJoinMultiJvmNode1 extends MembershipChangeListenerJoinSpec with FailureDetectorPuppetStrategy
class MembershipChangeListenerJoinMultiJvmNode2 extends MembershipChangeListenerJoinSpec with FailureDetectorPuppetStrategy

abstract class MembershipChangeListenerJoinSpec
  extends MultiNodeSpec(MembershipChangeListenerJoinMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MembershipChangeListenerJoinMultiJvmSpec._
  import ClusterEvent._

  "A registered MembershipChangeListener" must {
    "be notified when new node is JOINING" taggedAs LongRunningTest in {

      runOn(first) {
        val joinLatch = TestLatch()
        val expectedAddresses = Set(first, second) map address
        cluster.subscribe(system.actorOf(Props(new Actor {
          var members = Set.empty[Member]
          def receive = {
            case state: CurrentClusterState ⇒ members = state.members
            case MemberJoined(m) ⇒
              members = members - m + m
              if (members.map(_.address) == expectedAddresses)
                joinLatch.countDown()
            case _ ⇒ // ignore
          }
        })), classOf[MemberEvent])
        enterBarrier("registered-listener")

        joinLatch.await
      }

      runOn(second) {
        enterBarrier("registered-listener")
        cluster.join(first)
      }

      awaitUpConvergence(2)

      enterBarrier("after")
    }
  }
}
