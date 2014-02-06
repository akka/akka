/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import scala.concurrent.duration._
import akka.testkit._
import akka.testkit.TestEvent._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.remote.testconductor.RoleName
import akka.actor.Props
import akka.actor.Actor
import scala.util.control.NoStackTrace
import akka.remote.QuarantinedEvent
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteActorRefProvider
import akka.actor.ActorRef
import akka.dispatch.sysmsg.Failed

object SurviveNetworkInstabilityMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")
  val sixth = role("sixth")
  val seventh = role("seventh")
  val eighth = role("eighth")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("akka.remote.system-message-buffer-size=20")).
    withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)

  deployOn(second, """"/parent/*" {
      remote = "@third@"
    }""")

  class Parent extends Actor {
    def receive = {
      case p: Props ⇒ sender() ! context.actorOf(p)
    }
  }

  class RemoteChild extends Actor {
    import context.dispatcher

    def receive = {
      case "hello" ⇒
        context.system.scheduler.scheduleOnce(2.seconds, self, "boom")
        sender() ! "hello"
      case "boom" ⇒ throw new SimulatedException
    }
  }

  class Echo extends Actor {
    def receive = {
      case m ⇒ sender ! m
    }
  }

  class SimulatedException extends RuntimeException("Simulated") with NoStackTrace
}

class SurviveNetworkInstabilityMultiJvmNode1 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode2 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode3 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode4 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode5 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode6 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode7 extends SurviveNetworkInstabilitySpec
class SurviveNetworkInstabilityMultiJvmNode8 extends SurviveNetworkInstabilitySpec

abstract class SurviveNetworkInstabilitySpec
  extends MultiNodeSpec(SurviveNetworkInstabilityMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender {

  import SurviveNetworkInstabilityMultiJvmSpec._

  //  muteMarkingAsUnreachable()
  //  muteMarkingAsReachable()

  override def expectedTestDuration = 3.minutes

  def assertUnreachable(subjects: RoleName*): Unit = {
    val expected = subjects.toSet map address
    awaitAssert(clusterView.unreachableMembers.map(_.address) should be(expected))
  }

  system.actorOf(Props[Echo], "echo")

  def assertCanTalk(alive: RoleName*): Unit = {
    runOn(alive: _*) {
      for (to ← alive) {
        val sel = system.actorSelection(node(to) / "user" / "echo")
        awaitAssert {
          sel ! "ping"
          expectMsg(1.second, "ping")
        }
      }
    }
    enterBarrier("ping-ok")
  }

  "A network partition tolerant cluster" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth, fifth)

      enterBarrier("after-1")
      assertCanTalk(first, second, third, fourth, fifth)
    }

    "heal after a broken pair" taggedAs LongRunningTest in within(45.seconds) {
      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
      }
      enterBarrier("blackhole-2")

      runOn(first) { assertUnreachable(second) }
      runOn(second) { assertUnreachable(first) }
      runOn(third, fourth, fifth) {
        assertUnreachable(first, second)
      }

      enterBarrier("unreachable-2")

      runOn(first) {
        testConductor.passThrough(first, second, Direction.Both).await
      }
      enterBarrier("repair-2")

      // This test illustrates why we can't ignore gossip from unreachable aggregated
      // status. If all third, fourth, and fifth has been infected by first and second
      // unreachable they must accept gossip from first and second when their
      // broken connection has healed, otherwise they will be isolated forever.

      awaitAllReachable()
      enterBarrier("after-2")
      assertCanTalk(first, second, third, fourth, fifth)
    }

    "heal after one isolated node" taggedAs LongRunningTest in within(45.seconds) {
      val others = Vector(second, third, fourth, fifth)
      runOn(first) {
        for (other ← others) {
          testConductor.blackhole(first, other, Direction.Both).await
        }
      }
      enterBarrier("blackhole-3")

      runOn(first) { assertUnreachable(others: _*) }
      runOn(others: _*) {
        assertUnreachable(first)
      }

      enterBarrier("unreachable-3")

      runOn(first) {
        for (other ← others) {
          testConductor.passThrough(first, other, Direction.Both).await
        }
      }
      enterBarrier("repair-3")
      awaitAllReachable()
      enterBarrier("after-3")
      assertCanTalk((others :+ first): _*)
    }

    "heal two isolated islands" taggedAs LongRunningTest in within(45.seconds) {
      val island1 = Vector(first, second)
      val island2 = Vector(third, fourth, fifth)
      runOn(first) {
        // split the cluster in two parts (first, second) / (third, fourth, fifth)
        for (role1 ← island1; role2 ← island2) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("blackhole-4")

      runOn(island1: _*) {
        assertUnreachable(island2: _*)
      }
      runOn(island2: _*) {
        assertUnreachable(island1: _*)
      }

      enterBarrier("unreachable-4")

      runOn(first) {
        for (role1 ← island1; role2 ← island2) {
          testConductor.passThrough(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("repair-4")
      awaitAllReachable()
      enterBarrier("after-4")
      assertCanTalk((island1 ++ island2): _*)
    }

    "heal after unreachable when ring is changed" taggedAs LongRunningTest in within(60.seconds) {
      val joining = Vector(sixth, seventh)
      val others = Vector(second, third, fourth, fifth)
      runOn(first) {
        for (role1 ← (joining :+ first); role2 ← others) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("blackhole-5")

      runOn(first) { assertUnreachable(others: _*) }
      runOn(others: _*) { assertUnreachable(first) }

      enterBarrier("unreachable-5")

      runOn(joining: _*) {
        cluster.join(first)

        // let them join and stabilize heartbeating
        Thread.sleep(5000)
      }

      enterBarrier("joined-5")

      runOn((joining :+ first): _*) { assertUnreachable(others: _*) }
      // others doesn't know about the joining nodes yet, no gossip passed through
      runOn(others: _*) { assertUnreachable(first) }

      enterBarrier("more-unreachable-5")

      runOn(first) {
        for (role1 ← (joining :+ first); role2 ← others) {
          testConductor.passThrough(role1, role2, Direction.Both).await
        }
      }

      enterBarrier("repair-5")
      runOn((joining ++ others): _*) {
        awaitAllReachable()
        // eighth not joined yet
        awaitMembersUp(roles.size - 1)
      }
      enterBarrier("after-5")
      assertCanTalk((joining ++ others): _*)
    }

    "down and remove quarantined node" taggedAs LongRunningTest in within(60.seconds) {
      val others = Vector(first, third, fourth, fifth, sixth, seventh)

      runOn(second) {
        val sysMsgBufferSize = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].
          remoteSettings.SysMsgBufferSize
        val parent = system.actorOf(Props[Parent], "parent")
        // fill up the system message redeliver buffer with many failing actors
        for (_ ← 1 to sysMsgBufferSize + 1) {
          // remote deployment to third
          parent ! Props[RemoteChild]
          val child = expectMsgType[ActorRef]
          child ! "hello"
          expectMsg("hello")
          lastSender.path.address should be(address(third))
        }
      }
      runOn(third) {
        // undelivered system messages in RemoteChild on third should trigger QuarantinedEvent
        system.eventStream.subscribe(testActor, classOf[QuarantinedEvent])

        // after quarantined it will drop the Failed messages to deadLetters
        muteDeadLetters(classOf[Failed])(system)
      }
      enterBarrier("children-deployed")

      runOn(first) {
        for (role ← others)
          testConductor.blackhole(second, role, Direction.Send).await
      }
      enterBarrier("blackhole-6")

      runOn(third) {
        // undelivered system messages in RemoteChild on third should trigger QuarantinedEvent
        within(10.seconds) {
          expectMsgType[QuarantinedEvent].address should be(address(second))
        }
        system.eventStream.unsubscribe(testActor, classOf[QuarantinedEvent])
      }
      enterBarrier("quarantined")

      runOn(others: _*) {
        // second should be removed because of quarantine
        awaitAssert(clusterView.members.map(_.address) should not contain (address(second)))
      }

      enterBarrier("after-6")
      assertCanTalk(others: _*)
    }

    "continue and move Joining to Up after downing of one half" taggedAs LongRunningTest in within(60.seconds) {
      // note that second is already removed in previous step
      val side1 = Vector(first, third, fourth)
      val side1AfterJoin = side1 :+ eighth
      val side2 = Vector(fifth, sixth, seventh)
      runOn(first) {
        for (role1 ← side1AfterJoin; role2 ← side2) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("blackhole-7")

      runOn(side1: _*) { assertUnreachable(side2: _*) }
      runOn(side2: _*) { assertUnreachable(side1: _*) }

      enterBarrier("unreachable-7")

      runOn(eighth) {
        cluster.join(third)
      }
      runOn(fourth) {
        for (role2 ← side2) {
          cluster.down(role2)
        }
      }

      enterBarrier("downed-7")

      runOn(side1AfterJoin: _*) {
        // side2 removed
        val expected = (side1AfterJoin map address).toSet
        awaitAssert(clusterView.members.map(_.address) should be(expected))
        awaitAssert(clusterView.members.collectFirst { case m if m.address == address(eighth) ⇒ m.status } should be(
          Some(MemberStatus.Up)))
      }

      enterBarrier("side2-removed")

      runOn(first) {
        for (role1 ← side1AfterJoin; role2 ← side2) {
          testConductor.passThrough(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("repair-7")

      // side2 should not detect side1 as reachable again
      Thread.sleep(10000)

      runOn(side1AfterJoin: _*) {
        val expected = (side1AfterJoin map address).toSet
        clusterView.members.map(_.address) should be(expected)
      }

      runOn(side2: _*) {
        val expected = ((side2 ++ side1) map address).toSet
        clusterView.members.map(_.address) should be(expected)
        assertUnreachable(side1: _*)
      }

      enterBarrier("after-7")
      assertCanTalk((side1AfterJoin): _*)
    }

  }

}