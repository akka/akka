package sample.zeromq.pubsub.actor

import akka.actor.{ Actor, ActorLogging }
import akka.zeromq.ZMQMessage

class SubscriberActor extends Actor with ActorLogging {
  log.info("Connecting..")

  var startTime = System.nanoTime
  var counter = 0

  def receive = {
    case m: ZMQMessage ⇒ processMessage(m)
  }

  private def processMessage(m: ZMQMessage) = {
    counter += 1
    if (counter >= 3000) {
      val current = System.nanoTime
      val span = (current - startTime).toDouble
      log.info("Rate: " + 1000 * counter.toDouble / span)
      startTime = current
      counter = 0
    }
  }
}