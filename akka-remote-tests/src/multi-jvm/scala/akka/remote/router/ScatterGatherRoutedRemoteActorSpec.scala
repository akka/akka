/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.router

import language.postfixOps

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import scala.concurrent.Await
import akka.pattern.ask
import akka.remote.testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.routing.Broadcast
import akka.routing.ScatterGatherFirstCompletedRouter
import akka.routing.RoutedActorRef
import akka.testkit._
import akka.testkit.TestEvent._
import scala.concurrent.util.duration._
import akka.actor.PoisonPill
import akka.actor.Address

object ScatterGatherRoutedRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor {
    def receive = {
      case "hit" ⇒ sender ! self
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false))

  deployOnAll("""
      /service-hello.router = "scatter-gather"
      /service-hello.nr-of-instances = 3
      /service-hello.target.nodes = ["@first@", "@second@", "@third@"]
    """)
}

class ScatterGatherRoutedRemoteActorMultiJvmNode1 extends ScatterGatherRoutedRemoteActorSpec
class ScatterGatherRoutedRemoteActorMultiJvmNode2 extends ScatterGatherRoutedRemoteActorSpec
class ScatterGatherRoutedRemoteActorMultiJvmNode3 extends ScatterGatherRoutedRemoteActorSpec
class ScatterGatherRoutedRemoteActorMultiJvmNode4 extends ScatterGatherRoutedRemoteActorSpec

class ScatterGatherRoutedRemoteActorSpec extends MultiNodeSpec(ScatterGatherRoutedRemoteActorMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender with DefaultTimeout {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._

  def initialParticipants = roles.size

  "A new remote actor configured with a ScatterGatherFirstCompleted router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" taggedAs LongRunningTest in {

      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*")))

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(ScatterGatherFirstCompletedRouter(within = 10 seconds)), "service-hello")
        actor.isInstanceOf[RoutedActorRef] must be(true)

        val connectionCount = 3
        val iterationCount = 10

        for (i ← 0 until iterationCount; k ← 0 until connectionCount) {
          actor ! "hit"
        }

        val replies: Map[Address, Int] = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ ref.path.address
        }).foldLeft(Map(node(first).address -> 0, node(second).address -> 0, node(third).address -> 0)) {
          case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
        }

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        replies.values.sum must be === connectionCount * iterationCount
        replies.get(node(fourth).address) must be(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        enterBarrier("done")
      }

      enterBarrier("done")
    }
  }
}
