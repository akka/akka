
package se.scalablesolutions.akka.osgi.sample

import se.scalablesolutions.akka.actor.Actor

case class CounterMessage(counter: Int)

class Ping extends Actor {
  def receive = {
    case CounterMessage(i) => println("Got message " + i)
  }
}

class Pong extends Actor {
  def receive = {
    case _ =>
  }
}
