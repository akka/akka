/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.router

import language.postfixOps

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.Address
import scala.concurrent.Await
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.Broadcast
import akka.routing.RoundRobinRouter
import akka.routing.RoutedActorRef
import akka.routing.Resizer
import akka.routing.RouteeProvider
import akka.testkit._
import scala.concurrent.util.duration._

object RoundRobinRoutedRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor {
    def receive = {
      case "hit" ⇒ sender ! self
    }
  }

  class TestResizer extends Resizer {
    def isTimeForResize(messageCounter: Long): Boolean = messageCounter <= 10
    def resize(props: Props, routeeProvider: RouteeProvider): Unit = {
      val newRoutees = routeeProvider.createRoutees(props, nrOfInstances = 1, Nil)
      routeeProvider.registerRoutees(newRoutees)
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

      /service-hello2.router = "round-robin"
      /service-hello2.target.nodes = ["@first@", "@second@", "@third@"]
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
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" taggedAs LongRunningTest in {

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")
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
        replies.values foreach { _ must be(iterationCount) }
        replies.get(node(fourth).address) must be(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        enterBarrier("done")
      }
    }
  }

  "A new remote actor configured with a RoundRobin router and Resizer" must {
    "be locally instantiated on a remote node after several resize rounds" taggedAs LongRunningTest in {

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter(
          resizer = Some(new TestResizer))), "service-hello2")
        actor.isInstanceOf[RoutedActorRef] must be(true)

        val iterationCount = 9

        val repliesFrom: Set[ActorRef] =
          (for {
            i ← 0 until iterationCount
          } yield {
            actor ! "hit"
            receiveOne(5 seconds) match { case ref: ActorRef ⇒ ref }
          }).toSet

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        // at least more than one actor per node
        repliesFrom.size must be > (3)
        val repliesFromAddresses = repliesFrom.map(_.path.address)
        repliesFromAddresses must be === (Set(node(first), node(second), node(third)).map(_.address))

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        enterBarrier("done")
      }
    }
  }
}
