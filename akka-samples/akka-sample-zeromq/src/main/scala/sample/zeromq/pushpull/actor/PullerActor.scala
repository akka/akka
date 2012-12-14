package sample.zeromq.pushpull.actor

import akka.actor.Actor
import akka.zeromq.ZMQMessage
import compat.Platform

class PullerActor extends Actor {
  println("Connecting..")

  var startTime = Platform.currentTime
  var counter = 0

  def receive = {
    case m: ZMQMessage ⇒ processMessage(m)
    case _             ⇒ {}
  }

  private def processMessage(m: ZMQMessage) = {
    counter += 1
    if (counter >= 3000) {
      val current = Platform.currentTime
      val span = (current - startTime).toDouble
      println("Rate: " + 1000 * counter.toDouble / span)
      startTime = current
      counter = 0
    }
  }
}