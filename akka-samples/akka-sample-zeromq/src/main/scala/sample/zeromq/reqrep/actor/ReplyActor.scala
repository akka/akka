package sample.zeromq.reqrep.actor

import akka.actor.{ Actor, ActorLogging }
import akka.zeromq.ZMQMessage

class ReplyActor extends Actor with ActorLogging {

  log.info("Listening..")

  def receive = {
    case m: ZMQMessage ⇒ sender ! m
  }

}