/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import scala.concurrent.duration._
import akka.testkit._
import akka.testkit.TestEvent._
import java.util.concurrent.ThreadLocalRandom
import akka.remote.testconductor.RoleName
import akka.actor.Props
import akka.actor.Actor
import scala.util.control.NoStackTrace
import akka.remote.QuarantinedEvent
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteActorRefProvider
import akka.actor.ActorRef
import akka.dispatch.sysmsg.Failed
import akka.actor.PoisonPill
import akka.actor.Terminated

object SplitMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)

  class Echo extends Actor {
    def receive = {
      case m ⇒ sender ! m
    }
  }

  case class Targets(refs: Set[ActorRef])
  case object TargetsRegistered

  class Watcher extends Actor {
    var targets = Set.empty[ActorRef]

    def receive = {
      case Targets(refs) ⇒
        targets = refs
        sender() ! TargetsRegistered
      case "boom" ⇒
        targets.foreach(context.watch)
      case Terminated(_) ⇒
    }
  }

  class SimulatedException extends RuntimeException("Simulated") with NoStackTrace
}

class SplitMultiJvmNode1 extends SplitSpec
class SplitMultiJvmNode2 extends SplitSpec
class SplitMultiJvmNode3 extends SplitSpec
class SplitMultiJvmNode4 extends SplitSpec

abstract class SplitSpec
  extends MultiNodeSpec(SplitMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender {

  import SplitMultiJvmSpec._

  //  muteMarkingAsUnreachable()
  //  muteMarkingAsReachable()

  override def expectedTestDuration = 3.minutes

  def assertUnreachable(subjects: RoleName*): Unit = {
    val expected = subjects.toSet map address
    awaitAssert(clusterView.unreachableMembers.map(_.address) should ===(expected))
  }

  system.actorOf(Props[Echo], "echo")

  def assertCanTalk(alive: RoleName*): Unit = {
    runOn(alive: _*) {
      awaitAllReachable()
    }
    enterBarrier("reachable-ok")

    runOn(alive: _*) {
      for (to ← alive) {
        val sel = system.actorSelection(node(to) / "user" / "echo")
        val msg = s"ping-$to"
        val p = TestProbe()
        awaitAssert {
          sel.tell(msg, p.ref)
          p.expectMsg(1.second, msg)
        }
        p.ref ! PoisonPill
      }
    }
    enterBarrier("ping-ok")
  }

  "A network partition tolerant cluster" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth)

      enterBarrier("after-1")
      assertCanTalk(first, second, third, fourth)
    }

    "continue and move Joining to Up after downing of one half" taggedAs LongRunningTest in within(60.seconds) {
      // note that second is already removed in previous step
      val side1 = Vector(first, second)
      val side2 = Vector(third, fourth)
      runOn(first) {
        for (role1 ← side1; role2 ← side2) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("blackhole")

      runOn(side1: _*) { assertUnreachable(side2: _*) }
      runOn(side2: _*) { assertUnreachable(side1: _*) }

      enterBarrier("unreachable")

      runOn(second) {
        log.info("manual downing of side 2")
        for (role2 ← side2) {
          cluster.down(role2)
        }
      }

      enterBarrier("downed")

      runOn(side1: _*) {
        // side2 removed
        val expected = (side1 map address).toSet
        awaitAssert {
          // repeat the downing in case it was not successful, which may
          // happen if the removal was reverted due to gossip merge, see issue #18767
          runOn(fourth) {
            for (role2 ← side2) {
              cluster.down(role2)
            }
          }

          clusterView.members.map(_.address) should ===(expected)
        }
      }

      enterBarrier("side2-removed")

      runOn(first) {
        for (role1 ← side1; role2 ← side2) {
          testConductor.passThrough(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("repaired")

      // side2 should not detect side1 as reachable again
      Thread.sleep(10000)

      runOn(side1: _*) {
        val expected = (side1 map address).toSet
        clusterView.members.map(_.address) should ===(expected)
      }

      runOn(side2: _*) {
        val expected = ((side2 ++ side1) map address).toSet
        clusterView.members.map(_.address) should ===(expected)
        assertUnreachable(side1: _*)
      }

      enterBarrier("after")
      assertCanTalk((side1): _*)
    }

  }

}
