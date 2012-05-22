/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.routing

import akka.routing.RoundRobinRouter
import akka.actor.{ ActorRef, Props, Actor, ActorSystem }
import akka.routing.DefaultResizer
import akka.routing.RemoteRouterConfig

case class Message1(nbr: Int)

class ExampleActor1 extends Actor {
  def receive = {
    case Message1(nbr) ⇒ println("Received %s in router %s".format(nbr, self.path.name))
  }
}

object RoutingProgrammaticallyExample extends App {
  val system = ActorSystem("RPE")
  //#programmaticRoutingNrOfInstances
  val router1 = system.actorOf(Props[ExampleActor1].withRouter(
    RoundRobinRouter(nrOfInstances = 5)))
  //#programmaticRoutingNrOfInstances
  1 to 6 foreach { i ⇒ router1 ! Message1(i) }

  //#programmaticRoutingRoutees
  val actor1 = system.actorOf(Props[ExampleActor1])
  val actor2 = system.actorOf(Props[ExampleActor1])
  val actor3 = system.actorOf(Props[ExampleActor1])
  val routees = Vector[ActorRef](actor1, actor2, actor3)
  val router2 = system.actorOf(Props().withRouter(
    RoundRobinRouter(routees = routees)))
  //#programmaticRoutingRoutees
  1 to 6 foreach { i ⇒ router2 ! Message1(i) }

  //#programmaticRoutingWithResizer
  val resizer = DefaultResizer(lowerBound = 2, upperBound = 15)
  val router3 = system.actorOf(Props[ExampleActor1].withRouter(
    RoundRobinRouter(resizer = Some(resizer))))
  //#programmaticRoutingWithResizer
  1 to 6 foreach { i ⇒ router3 ! Message1(i) }

  //#remoteRoutees
  import akka.actor.{ Address, AddressFromURIString }
  val addresses = Seq(
    Address("akka", "remotesys", "otherhost", 1234),
    AddressFromURIString("akka://othersys@anotherhost:1234"))
  val routerRemote = system.actorOf(Props[ExampleActor1].withRouter(
    RemoteRouterConfig(RoundRobinRouter(5), addresses)))
  //#remoteRoutees

}