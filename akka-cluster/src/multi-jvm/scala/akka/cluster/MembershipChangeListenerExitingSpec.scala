/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Props
import akka.actor.Actor
import akka.cluster.MemberStatus._
import akka.actor.Deploy

object MembershipChangeListenerExitingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
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
        val exitingLatch = TestLatch()
        val removedLatch = TestLatch()
        val secondAddress = address(second)
        cluster.subscribe(
          system.actorOf(Props(new Actor {
            def receive = {
              case state: CurrentClusterState =>
                if (state.members.exists(m => m.address == secondAddress && m.status == Exiting))
                  exitingLatch.countDown()
              case MemberExited(m) if m.address == secondAddress =>
                exitingLatch.countDown()
              case MemberRemoved(m, Exiting) if m.address == secondAddress =>
                removedLatch.countDown()
              case _ => // ignore
            }
          }).withDeploy(Deploy.local)),
          classOf[MemberEvent])
        enterBarrier("registered-listener")
        exitingLatch.await
        removedLatch.await
      }

      runOn(third) {
        val exitingLatch = TestLatch()
        val secondAddress = address(second)
        cluster.subscribe(
          system.actorOf(Props(new Actor {
            def receive = {
              case state: CurrentClusterState =>
                if (state.members.exists(m => m.address == secondAddress && m.status == Exiting))
                  exitingLatch.countDown()
              case MemberExited(m) if m.address == secondAddress =>
                exitingLatch.countDown()
              case _ => // ignore
            }
          }).withDeploy(Deploy.local)),
          classOf[MemberEvent])
        enterBarrier("registered-listener")
        exitingLatch.await
      }

      enterBarrier("finished")
    }
  }
}
