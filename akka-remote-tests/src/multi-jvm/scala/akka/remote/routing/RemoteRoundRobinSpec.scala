/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.routing

import akka.actor.{ Actor, ActorRef, Address, PoisonPill, Props }
import akka.pattern.ask
import akka.remote.RemotingMultiNodeSpec
import akka.remote.testkit.MultiNodeConfig
import akka.routing._
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class RemoteRoundRobinConfig(artery: Boolean) extends MultiNodeConfig {

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
      akka.remote.artery.enabled = $artery
      """)).withFallback(RemotingMultiNodeSpec.commonConfig))

  deployOnAll("""
      /service-hello {
        router = round-robin-pool
        nr-of-instances = 3
        target.nodes = ["@first@", "@second@", "@third@"]
      }

      /service-hello2 {
        router = round-robin-pool
        target.nodes = ["@first@", "@second@", "@third@"]
      }

      /service-hello3 {
        router = round-robin-group
        routees.paths = [
          "@first@/user/target-first",
          "@second@/user/target-second",
          "@third@/user/target-third"]
      }
    """)
}

class RemoteRoundRobinMultiJvmNode1 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = false))
class RemoteRoundRobinMultiJvmNode2 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = false))
class RemoteRoundRobinMultiJvmNode3 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = false))
class RemoteRoundRobinMultiJvmNode4 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = false))

class ArteryRemoteRoundRobinMultiJvmNode1 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = true))
class ArteryRemoteRoundRobinMultiJvmNode2 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = true))
class ArteryRemoteRoundRobinMultiJvmNode3 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = true))
class ArteryRemoteRoundRobinMultiJvmNode4 extends RemoteRoundRobinSpec(new RemoteRoundRobinConfig(artery = true))

object RemoteRoundRobinSpec {
  class SomeActor extends Actor {
    def receive = {
      case "hit" ⇒ sender() ! self
    }
  }

  class TestResizer extends Resizer {
    override def isTimeForResize(messageCounter: Long): Boolean = messageCounter <= 10
    override def resize(currentRoutees: immutable.IndexedSeq[Routee]): Int = 1
  }
}

class RemoteRoundRobinSpec(multiNodeConfig: RemoteRoundRobinConfig) extends RemotingMultiNodeSpec(multiNodeConfig)
  with DefaultTimeout {
  import RemoteRoundRobinSpec._
  import multiNodeConfig._

  def initialParticipants = roles.size

  "A remote round robin pool" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(RoundRobinPool(nrOfInstances = 0).props(Props[SomeActor]), "service-hello")
        actor.isInstanceOf[RoutedActorRef] should ===(true)

        val connectionCount = 3
        val iterationCount = 10

        for (i ← 0 until iterationCount; k ← 0 until connectionCount) {
          actor ! "hit"
        }

        val replies: Map[Address, Int] = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ ref.path.address
        }).foldLeft(Map(node(first).address → 0, node(second).address → 0, node(third).address → 0)) {
          case (replyMap, address) ⇒ replyMap + (address → (replyMap(address) + 1))
        }

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        replies.values foreach { _ should ===(iterationCount) }
        replies.get(node(fourth).address) should ===(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
      }

      enterBarrier("done")
    }
  }

  "A remote round robin pool with resizer" must {
    "be locally instantiated on a remote node after several resize rounds" in within(5 seconds) {

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(RoundRobinPool(
          nrOfInstances = 1,
          resizer = Some(new TestResizer)).props(Props[SomeActor]), "service-hello2")
        actor.isInstanceOf[RoutedActorRef] should ===(true)

        actor ! GetRoutees
        // initial nrOfInstances 1 + initial resize => 2
        expectMsgType[Routees].routees.size should ===(2)

        val repliesFrom: Set[ActorRef] =
          (for (n ← 3 to 9) yield {
            // each message trigger a resize, incrementing number of routees with 1
            actor ! "hit"
            Await.result(actor ? GetRoutees, timeout.duration).asInstanceOf[Routees].routees.size should ===(n)
            expectMsgType[ActorRef]
          }).toSet

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        repliesFrom.size should ===(7)
        val repliesFromAddresses = repliesFrom.map(_.path.address)
        repliesFromAddresses should ===(Set(node(first), node(second), node(third)).map(_.address))

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
      }

      enterBarrier("done")
    }
  }

  "A remote round robin group" must {
    "send messages with actor selection to remote paths" in {

      runOn(first, second, third) {
        system.actorOf(Props[SomeActor], name = "target-" + myself.name)
        enterBarrier("start", "end")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(FromConfig.props(), "service-hello3")
        actor.isInstanceOf[RoutedActorRef] should ===(true)

        val connectionCount = 3
        val iterationCount = 10

        for (i ← 0 until iterationCount; k ← 0 until connectionCount) {
          actor ! "hit"
        }

        val replies: Map[Address, Int] = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ ref.path.address
        }).foldLeft(Map(node(first).address → 0, node(second).address → 0, node(third).address → 0)) {
          case (replyMap, address) ⇒ replyMap + (address → (replyMap(address) + 1))
        }

        enterBarrier("end")
        replies.values foreach { _ should ===(iterationCount) }
        replies.get(node(fourth).address) should ===(None)
      }

      enterBarrier("done")
    }
  }
}
