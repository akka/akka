/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.router

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.dispatch.Await
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.Broadcast
import akka.routing.RoundRobinRouter
import akka.routing.RoutedActorRef
import akka.testkit._

object RoundRobinRoutedRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hit" ⇒ sender ! self
      case "end" ⇒ context.stop(self)
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false))

  deployOnAll("""
      /service-hello.router = "round-robin"
      /service-hello.nr-of-instances = 3
      /service-hello.target.nodes = ["@first@", "@second@", "@third@"]
    """)
}

class RoundRobinRoutedRemoteActorMultiJvmNode1 extends RoundRobinRoutedRemoteActorSpec
class RoundRobinRoutedRemoteActorMultiJvmNode2 extends RoundRobinRoutedRemoteActorSpec
class RoundRobinRoutedRemoteActorMultiJvmNode3 extends RoundRobinRoutedRemoteActorSpec
class RoundRobinRoutedRemoteActorMultiJvmNode4 extends RoundRobinRoutedRemoteActorSpec

class RoundRobinRoutedRemoteActorSpec extends MultiNodeSpec(RoundRobinRoutedRemoteActorMultiJvmSpec)
  with ImplicitSender with DefaultTimeout {
  import RoundRobinRoutedRemoteActorMultiJvmSpec._

  def initialParticipants = 4

  "A new remote actor configured with a RoundRobin router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      runOn(first, second, third) {
        testConductor.enter("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        testConductor.enter("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")
        actor.isInstanceOf[RoutedActorRef] must be(true)

        val connectionCount = 3
        val iterationCount = 10

        var replies = Map(
          node(first).address -> 0,
          node(second).address -> 0,
          node(third).address -> 0)

        for (i ← 0 until iterationCount) {
          for (k ← 0 until connectionCount) {
            val nodeAddress = Await.result(actor ? "hit", timeout.duration).asInstanceOf[ActorRef].path.address
            replies = replies + (nodeAddress -> (replies(nodeAddress) + 1))
          }
        }

        testConductor.enter("broadcast-end")
        actor ! Broadcast("end")

        testConductor.enter("end")
        replies.values foreach { _ must be(10) }

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        testConductor.enter("done")
      }
    }
  }
}
