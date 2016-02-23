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
import akka.remote.testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.routing.Broadcast
import akka.routing.RandomPool
import akka.routing.RoutedActorRef
import akka.testkit._

object RemoteRandomMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor {
    def receive = {
      case "hit" ⇒ sender() ! self
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false))

  deployOnAll("""
      /service-hello {
        router = "random-pool"
        nr-of-instances = 3
        target.nodes = ["@first@", "@second@", "@third@"]
      }
    """)
}

class RemoteRandomMultiJvmNode1 extends RemoteRandomSpec
class RemoteRandomMultiJvmNode2 extends RemoteRandomSpec
class RemoteRandomMultiJvmNode3 extends RemoteRandomSpec
class RemoteRandomMultiJvmNode4 extends RemoteRandomSpec

class RemoteRandomSpec extends MultiNodeSpec(RemoteRandomMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender with DefaultTimeout {
  import RemoteRandomMultiJvmSpec._

  def initialParticipants = roles.size

  "A remote random pool" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" taggedAs LongRunningTest in {

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(RandomPool(nrOfInstances = 0).props(Props[SomeActor]), "service-hello")
        actor.isInstanceOf[RoutedActorRef] should ===(true)

        val connectionCount = 3
        val iterationCount = 100

        for (i ← 0 until iterationCount; k ← 0 until connectionCount) {
          actor ! "hit"
        }

        val replies: Map[Address, Int] = (receiveWhile(5.seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ ref.path.address
        }).foldLeft(Map(node(first).address -> 0, node(second).address -> 0, node(third).address -> 0)) {
          case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
        }

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        // since it's random we can't be too strict in the assert
        replies.values count (_ > 0) should be > (connectionCount - 2)
        replies.get(node(fourth).address) should ===(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        enterBarrier("done")
      }
    }
  }
}