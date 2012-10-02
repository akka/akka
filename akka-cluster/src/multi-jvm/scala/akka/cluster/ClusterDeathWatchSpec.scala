/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.testkit.TestEvent._
import akka.actor.Props
import akka.actor.Actor
import akka.actor.Address
import akka.actor.RootActorPath
import akka.actor.Terminated
import akka.actor.Address

object ClusterDeathWatchMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class ClusterDeathWatchMultiJvmNode1 extends ClusterDeathWatchSpec
class ClusterDeathWatchMultiJvmNode2 extends ClusterDeathWatchSpec
class ClusterDeathWatchMultiJvmNode3 extends ClusterDeathWatchSpec
class ClusterDeathWatchMultiJvmNode4 extends ClusterDeathWatchSpec

abstract class ClusterDeathWatchSpec
  extends MultiNodeSpec(ClusterDeathWatchMultiJvmSpec)
  with MultiNodeClusterSpec {

  import ClusterDeathWatchMultiJvmSpec._

  override def atStartup(): Unit = {
    super.atStartup()
    if (!log.isDebugEnabled) {
      muteMarkingAsUnreachable()
      system.eventStream.publish(Mute(EventFilter[java.net.UnknownHostException]()))
    }
  }

  "An actor watching a remote actor in the cluster" must {
    "receive Terminated when watched node becomes unreachable" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-up")

      runOn(first) {
        enterBarrier("subjected-started")

        val path2 = RootActorPath(second) / "user" / "subject"
        val path3 = RootActorPath(third) / "user" / "subject"
        val watchEstablished = TestLatch(1)
        system.actorOf(Props(new Actor {
          context.watch(context.actorFor(path2))
          context.watch(context.actorFor(path3))
          watchEstablished.countDown
          def receive = {
            case t: Terminated ⇒ testActor ! t.actor.path
          }
        }), name = "observer1")

        watchEstablished.await
        enterBarrier("watch-established")
        expectMsg(path2)
        expectNoMsg
        enterBarrier("second-terminated")

        markNodeAsUnavailable(third)
        expectMsg(path3)
        enterBarrier("third-terminated")

      }

      runOn(second, third, fourth) {
        system.actorOf(Props(new Actor { def receive = Actor.emptyBehavior }), name = "subject")
        enterBarrier("subjected-started")
        enterBarrier("watch-established")
        runOn(third) {
          markNodeAsUnavailable(second)
        }
        enterBarrier("second-terminated")
        enterBarrier("third-terminated")
      }

      enterBarrier("after-1")

    }

    "receive Terminated when watched node is unknown host" taggedAs LongRunningTest in {
      runOn(first) {
        val path = RootActorPath(Address("akka", system.name, "unknownhost", 2552)) / "user" / "subject"
        system.actorOf(Props(new Actor {
          context.watch(context.actorFor(path))
          def receive = {
            case t: Terminated ⇒ testActor ! t.actor.path
          }
        }), name = "observer2")

        expectMsg(path)
      }

      enterBarrier("after-2")
    }

    "receive Terminated when watched path doesn't exist" taggedAs LongRunningTest in {
      runOn(first) {
        val path = RootActorPath(second) / "user" / "non-existing"
        system.actorOf(Props(new Actor {
          context.watch(context.actorFor(path))
          def receive = {
            case t: Terminated ⇒ testActor ! t.actor.path
          }
        }), name = "observer3")

        expectMsg(path)
      }

      enterBarrier("after-3")
    }

  }
}
