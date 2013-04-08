/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.concurrent.duration._
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
import akka.remote.RemoteActorRef
import java.util.concurrent.TimeoutException
import akka.actor.ActorSystemImpl
import akka.actor.ActorIdentity
import akka.actor.Identify

object ClusterDeathWatchMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))

  deployOn(fourth, """/hello.remote = "@first@" """)

  class Hello extends Actor {
    def receive = Actor.emptyBehavior
  }
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
    "receive Terminated when watched node becomes Down" taggedAs LongRunningTest in within(20 seconds) {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-up")

      runOn(first) {
        enterBarrier("subjected-started")

        val path2 = RootActorPath(second) / "user" / "subject"
        val path3 = RootActorPath(third) / "user" / "subject"
        val watchEstablished = TestLatch(2)
        system.actorOf(Props(new Actor {
          context.actorSelection(path2) ! Identify(path2)
          context.actorSelection(path3) ! Identify(path3)

          def receive = {
            case ActorIdentity(`path2`, Some(ref)) ⇒
              context.watch(ref)
              watchEstablished.countDown
            case ActorIdentity(`path3`, Some(ref)) ⇒
              context.watch(ref)
              watchEstablished.countDown
            case Terminated(actor) ⇒ testActor ! actor.path
          }
        }), name = "observer1")

        watchEstablished.await
        enterBarrier("watch-established")
        expectMsg(path2)
        expectNoMsg(2 seconds)
        enterBarrier("second-terminated")

        markNodeAsUnavailable(third)
        awaitAssert(clusterView.members.map(_.address) must not contain (address(third)))
        awaitAssert(clusterView.unreachableMembers.map(_.address) must contain(address(third)))
        cluster.down(third)
        // removed
        awaitAssert(clusterView.unreachableMembers.map(_.address) must not contain (address(third)))
        expectMsg(path3)
        enterBarrier("third-terminated")

      }

      runOn(second, third, fourth) {
        system.actorOf(Props(new Actor { def receive = Actor.emptyBehavior }), name = "subject")
        enterBarrier("subjected-started")
        enterBarrier("watch-established")
        runOn(third) {
          markNodeAsUnavailable(second)
          awaitAssert(clusterView.members.map(_.address) must not contain (address(second)))
          awaitAssert(clusterView.unreachableMembers.map(_.address) must contain(address(second)))
          cluster.down(second)
          // removed
          awaitAssert(clusterView.unreachableMembers.map(_.address) must not contain (address(second)))
        }
        enterBarrier("second-terminated")
        enterBarrier("third-terminated")
      }

      enterBarrier("after-1")

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

      enterBarrier("after-2")
    }

    "be able to shutdown system when using remote deployed actor on node that crash" taggedAs LongRunningTest in within(20 seconds) {
      runOn(fourth) {
        val hello = system.actorOf(Props[Hello], "hello")
        hello.isInstanceOf[RemoteActorRef] must be(true)
        hello.path.address must be(address(first))
        watch(hello)
        enterBarrier("hello-deployed")

        markNodeAsUnavailable(first)
        awaitAssert(clusterView.members.map(_.address) must not contain (address(first)))
        awaitAssert(clusterView.unreachableMembers.map(_.address) must contain(address(first)))
        cluster.down(first)
        // removed
        awaitAssert(clusterView.unreachableMembers.map(_.address) must not contain (address(first)))

        val t = expectMsgType[Terminated]
        t.actor must be(hello)

        enterBarrier("first-unavailable")

        system.shutdown()
        val timeout = remaining
        try system.awaitTermination(timeout) catch {
          case _: TimeoutException ⇒
            fail("Failed to stop [%s] within [%s] \n%s".format(system.name, timeout,
              system.asInstanceOf[ActorSystemImpl].printTree))
        }
      }

      runOn(first, second, third) {
        enterBarrier("hello-deployed")
        enterBarrier("first-unavailable")
        runOn(first) {
          // fourth system will be shutdown, remove to not participate in barriers any more
          testConductor.removeNode(fourth)
        }

        enterBarrier("after-3")
      }

    }

  }
}
