/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.router

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.Address
import akka.dispatch.Await
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.Broadcast
import akka.routing.RandomRouter
import akka.routing.RoutedActorRef
import akka.testkit._
import akka.util.duration._

object RandomRoutedRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor with Serializable {
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
      /service-hello.router = "random"
      /service-hello.nr-of-instances = 3
      /service-hello.target.nodes = ["@first@", "@second@", "@third@"]
    """)
}

class RandomRoutedRemoteActorMultiJvmNode1 extends RandomRoutedRemoteActorSpec
class RandomRoutedRemoteActorMultiJvmNode2 extends RandomRoutedRemoteActorSpec
class RandomRoutedRemoteActorMultiJvmNode3 extends RandomRoutedRemoteActorSpec
class RandomRoutedRemoteActorMultiJvmNode4 extends RandomRoutedRemoteActorSpec

class RandomRoutedRemoteActorSpec extends MultiNodeSpec(RandomRoutedRemoteActorMultiJvmSpec)
  with ImplicitSender with DefaultTimeout {
  import RandomRoutedRemoteActorMultiJvmSpec._

  def initialParticipants = 4

  "A new remote actor configured with a Random router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" taggedAs LongRunningTest in {

      runOn(first, second, third) {
        enter("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        enter("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(RandomRouter()), "service-hello")
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

        enter("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enter("end")
        replies.values foreach { _ must be > (0) }
        replies.get(node(fourth).address) must be(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        enter("done")
      }
    }
  }
}
