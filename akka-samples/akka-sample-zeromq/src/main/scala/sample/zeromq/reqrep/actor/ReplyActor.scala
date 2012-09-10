package sample.zeromq.reqrep.actor

import akka.actor.Actor
import akka.zeromq.{ ZMQMessage, Frame }

class ReplyActor extends Actor {

  println("Listening..")

  def receive = {
    case m: ZMQMessage ⇒ sender ! m
    case _             ⇒ ()
  }

}