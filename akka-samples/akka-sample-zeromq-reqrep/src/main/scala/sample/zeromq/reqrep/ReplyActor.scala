package sample.zeromq.reqrep

import akka.actor.Actor
import akka.zeromq.{ Frame, ZMQMessage }
import util.Random

class ReplyActor extends Actor {

  println("Listening..")

  val random = new Random()
  val maxMessageSize = 10

  def receive = {
    case m: ZMQMessage ⇒
      val message = Util.randomString(random, maxMessageSize)
      sender ! ZMQMessage(Seq(Frame(message)))
    case _ ⇒
      sender ! ZMQMessage(Seq(Frame("didn't understand?")))
  }

}