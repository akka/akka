/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.actor

import akka.testkit.AkkaSpec

//#hello-world
import akka.actor.Actor
import akka.actor.Props

class HelloWorld extends Actor {

  override def preStart(): Unit = {
    // create the greeter actor
    val greeter = context.actorOf(Props[Greeter], "greeter")
    // tell it to perform the greeting
    greeter ! Greeter.Greet
  }

  def receive = {
    // when the greeter is done, stop this actor and with it the application
    case Greeter.Done ⇒ context.stop(self)
  }
}
//#hello-world

//#greeter
object Greeter {
  case object Greet
  case object Done
}

class Greeter extends Actor {
  def receive = {
    case Greeter.Greet ⇒
      println("Hello World!")
      sender ! Greeter.Done
  }
}
//#greeter

class IntroDocSpec extends AkkaSpec {

  "demonstrate HelloWorld" in {
    expectTerminated(watch(system.actorOf(Props[HelloWorld])))
  }

}