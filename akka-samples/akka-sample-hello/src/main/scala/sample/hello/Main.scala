/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.hello

import akka.actor.{ ActorSystem, Actor }

case object Start

object Main {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem()
    system.actorOf[HelloActor] ! Start
  }
}

class HelloActor extends Actor {
  val worldActor = system.actorOf[WorldActor]
  def receive = {
    case Start ⇒ worldActor ! "Hello"
    case s: String ⇒
      println("Received message: %s".format(s))
      system.stop()
  }
}

class WorldActor extends Actor {
  def receive = {
    case s: String ⇒ sender ! s.toUpperCase + " world!"
  }
}

