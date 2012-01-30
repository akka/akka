/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.kernel.hello

import akka.actor.{ Actor, ActorSystem, ActorLogging, Props }
import akka.kernel.Bootable

case object Start

class HelloActor extends Actor with ActorLogging {
  val worldActor = context.actorOf(Props[WorldActor], name = "world")

  def receive = {
    case Start ⇒ worldActor ! "Hello"
    case message: String ⇒
      log.info("Received message [{}]", message)
  }
}

class WorldActor extends Actor {
  def receive = {
    case message: String ⇒ sender ! (message.toUpperCase + " world!")
  }
}

class HelloKernel extends Bootable {
  val system = ActorSystem("hellokernel")

  def startup = {
    system.actorOf(Props[HelloActor], name = "hello") ! Start
  }

  def shutdown = {
    system.shutdown()
  }
}