/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.routing

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.PoisonPill
import akka.actor.Props
import akka.remote.RemotingMultiNodeSpec
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.routing.Broadcast
import akka.routing.ScatterGatherFirstCompletedPool
import akka.routing.RoutedActorRef
import akka.testkit._
import akka.testkit.TestEvent._
import com.typesafe.config.ConfigFactory

class RemoteScatterGatherConfig(artery: Boolean) extends MultiNodeConfig {

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
        router = "scatter-gather-pool"
        nr-of-instances = 3
        target.nodes = ["@first@", "@second@", "@third@"]
      }
    """)
}

class RemoteScatterGatherMultiJvmNode1 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = false))
class RemoteScatterGatherMultiJvmNode2 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = false))
class RemoteScatterGatherMultiJvmNode3 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = false))
class RemoteScatterGatherMultiJvmNode4 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = false))

class ArteryRemoteScatterGatherMultiJvmNode1 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = true))
class ArteryRemoteScatterGatherMultiJvmNode2 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = true))
class ArteryRemoteScatterGatherMultiJvmNode3 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = true))
class ArteryRemoteScatterGatherMultiJvmNode4 extends RemoteScatterGatherSpec(new RemoteScatterGatherConfig(artery = true))

object RemoteScatterGatherSpec {
  class SomeActor extends Actor {
    def receive = {
      case "hit" ⇒ sender() ! self
    }
  }
}

class RemoteScatterGatherSpec(multiNodeConfig: RemoteScatterGatherConfig) extends RemotingMultiNodeSpec(multiNodeConfig)
  with DefaultTimeout {
  import multiNodeConfig._
  import RemoteScatterGatherSpec._

  def initialParticipants = roles.size

  "A remote ScatterGatherFirstCompleted pool" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" taggedAs LongRunningTest in {

      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*")))

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(ScatterGatherFirstCompletedPool(nrOfInstances = 1, within = 10.seconds).props(Props[SomeActor]), "service-hello")
        actor.isInstanceOf[RoutedActorRef] should ===(true)

        val connectionCount = 3
        val iterationCount = 10

        for (i ← 0 until iterationCount; k ← 0 until connectionCount) {
          actor ! "hit"
        }

        val replies: Map[Address, Int] = (receiveWhile(5.seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ ref.path.address
        }).foldLeft(Map(node(first).address → 0, node(second).address → 0, node(third).address → 0)) {
          case (replyMap, address) ⇒ replyMap + (address → (replyMap(address) + 1))
        }

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        replies.values.sum should ===(connectionCount * iterationCount)
        replies.get(node(fourth).address) should ===(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        enterBarrier("done")
      }

      enterBarrier("done")
    }
  }
}
