/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.routing

import akka.routing.RoundRobinRouter
import akka.actor.{ ActorRef, Props, Actor, ActorSystem }

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
  val router2 = system.actorOf(Props[ExampleActor1].withRouter(
    RoundRobinRouter(targets = routees)))
  //#programmaticRoutingRoutees
  1 to 6 foreach { i ⇒ router2 ! Message1(i) }
}