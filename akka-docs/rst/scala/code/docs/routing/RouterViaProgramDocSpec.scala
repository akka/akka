/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.routing

import akka.actor._
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.routing.FromConfig
import akka.routing.RoundRobinRouter
import akka.testkit._
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import akka.actor.ActorPath

object RouterViaProgramDocSpec {
  case class Message1(nbr: Int)
  case class Reply1(name: String, m: Message1)

  class ExampleActor1 extends Actor {
    def receive = {
      case m @ Message1(nbr) ⇒ sender ! Reply1(self.path.name, m)
    }
  }

  class Echo extends Actor {
    def receive = {
      case m ⇒ sender ! m
    }
  }
}

class RouterViaProgramDocSpec extends AkkaSpec with ImplicitSender {
  import RouterViaProgramDocSpec._

  "demonstrate routees from paths" in {

    //#programmaticRoutingRouteePaths
    val actor1 = system.actorOf(Props[ExampleActor1], "actor1")
    val actor2 = system.actorOf(Props[ExampleActor1], "actor2")
    val actor3 = system.actorOf(Props[ExampleActor1], "actor3")
    val routees = Vector[String]("/user/actor1", "/user/actor2", "/user/actor3")
    val router = system.actorOf(
      Props().withRouter(RoundRobinRouter(routees = routees)))
    //#programmaticRoutingRouteePaths
    1 to 6 foreach { i ⇒ router ! Message1(i) }
    val received = receiveN(6, 5.seconds.dilated)
    1 to 6 foreach { i ⇒
      val expectedName = (routees((i - 1) % routees.length)).split("/").last
      val expectedMsg = Message1(i)
      received must contain[AnyRef](Reply1(expectedName, expectedMsg))
    }
  }

  "demonstrate broadcast" in {
    val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(nrOfInstances = 5)))
    //#broadcastDavyJonesWarning
    import akka.routing.Broadcast
    router ! Broadcast("Watch out for Davy Jones' locker")
    //#broadcastDavyJonesWarning
    receiveN(5, 5.seconds.dilated) must have length (5)
  }

  "demonstrate PoisonPill" in {
    val router = watch(system.actorOf(Props[Echo].withRouter(RoundRobinRouter(nrOfInstances = 5))))
    //#poisonPill
    import akka.actor.PoisonPill
    router ! PoisonPill
    //#poisonPill
    expectMsgPF() { case Terminated(`router`) ⇒ () }
  }

  "demonstrate broadcast of PoisonPill" in {
    val router = watch(system.actorOf(Props[Echo].withRouter(RoundRobinRouter(nrOfInstances = 5))))
    //#broadcastPoisonPill
    import akka.actor.PoisonPill
    import akka.routing.Broadcast
    router ! Broadcast(PoisonPill)
    //#broadcastPoisonPill
    expectMsgPF() { case Terminated(`router`) ⇒ () }
  }

  "demonstrate Kill" in {
    val router = watch(system.actorOf(Props[Echo].withRouter(RoundRobinRouter(nrOfInstances = 5))))
    //#kill
    import akka.actor.Kill
    router ! Kill
    //#kill
    expectMsgPF() { case Terminated(`router`) ⇒ () }
  }

  "demonstrate broadcast of Kill" in {
    val router = watch(system.actorOf(Props[Echo].withRouter(RoundRobinRouter(nrOfInstances = 5))))
    //#broadcastKill
    import akka.actor.Kill
    import akka.routing.Broadcast
    router ! Broadcast(Kill)
    //#broadcastKill
    expectMsgPF() { case Terminated(`router`) ⇒ () }
  }

}
