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
import akka.routing.ScatterGatherFirstCompletedRouter
import akka.routing.RoutedActorRef
import akka.testkit._
import akka.util.duration._

object ScatterGatherRoutedRemoteActorMultiJvmSpec extends MultiNodeConfig {

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
  with ImplicitSender with DefaultTimeout {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._

  def initialParticipants = 4

  "A new remote actor configured with a ScatterGatherFirstCompleted router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      runOn(first, second, third) {
        testConductor.enter("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        testConductor.enter("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(ScatterGatherFirstCompletedRouter(within = 10 seconds)), "service-hello")
        actor.isInstanceOf[RoutedActorRef] must be(true)

        val connectionCount = 3
        val iterationCount = 10

        for (i ← 0 until iterationCount) {
          for (k ← 0 until connectionCount) {
            actor ! "hit"
          }
        }

        val replies = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ (ref.path.address, 1)
        }).foldLeft(Map(node(first).address -> 0, node(second).address -> 0, node(third).address -> 0)) {
          case (m, (n, c)) ⇒ m + (n -> (m(n) + c))
        }

        testConductor.enter("broadcast-end")
        actor ! Broadcast("end")

        testConductor.enter("end")
        replies.values.sum must be === connectionCount * iterationCount

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        testConductor.enter("done")
      }
    }
  }
}
