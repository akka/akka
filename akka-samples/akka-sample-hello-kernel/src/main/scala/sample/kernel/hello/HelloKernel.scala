/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.kernel.hello

import akka.actor.{ Actor, ActorSystem, Props }
import akka.kernel.Bootable

case object Start

class HelloActor extends Actor {
  val worldActor = context.actorOf(Props[WorldActor])

  def receive = {
    case Start ⇒ worldActor ! "Hello"
    case message: String ⇒
      println("Received message '%s'" format message)
  }
}

class WorldActor extends Actor {
  def receive = {
    case message: String ⇒ sender ! (message.toUpperCase + " world!")
  }
}

class HelloKernel extends Bootable {
  def startup(system: ActorSystem) = {
    system.actorOf(Props[HelloActor]) ! Start
  }

  def shutdown(system: ActorSystem) = {}
}
