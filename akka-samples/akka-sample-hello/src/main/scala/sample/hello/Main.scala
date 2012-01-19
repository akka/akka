/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.hello

import akka.actor.{ ActorSystem, Actor, Props }

case object Start

object Main {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem()
    system.actorOf(Props[HelloActor]) ! Start
  }
}

class HelloActor extends Actor {
  val worldActor = context.actorOf(Props[WorldActor])
  def receive = {
    case Start ⇒ worldActor ! "Hello"
    case s: String ⇒
      println("Received message: %s".format(s))
      context.system.shutdown()
  }
}

class WorldActor extends Actor {
  def receive = {
    case s: String ⇒ sender ! s.toUpperCase + " world!"
  }
}

