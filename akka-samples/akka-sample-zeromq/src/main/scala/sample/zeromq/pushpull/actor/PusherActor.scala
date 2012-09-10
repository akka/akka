package sample.zeromq.pushpull.actor

import akka.actor.{ Actor }
import util.Random
import akka.zeromq._
import sample.zeromq.Util
import akka.zeromq.Listener
import akka.zeromq.Bind
import akka.event.Logging

class PusherActor extends Actor {
  val log = Logging(context.system, this)
  log.debug("Binding...")

  private sealed case class PushMessage()

  private val pusherActor = ZeroMQExtension(context.system).newPushSocket(
    Array(
      Bind("tcp://127.0.0.1:1234"),
      Listener(self)))

  val random = new Random()
  val maxMessageSize = 100

  var counter = 0

  def receive = {
    case m: ZMQMessage  ⇒ pusherActor ! m; pushMessage()
    case p: PushMessage ⇒ pushMessage()
    case Binding        ⇒ pushMessage()
    case _              ⇒ throw new Exception("unknown command")
  }

  private def pushMessage() = {
    val modulo = 256
    if (counter % modulo == 0) {
      val message = Util.randomString(random, maxMessageSize)
      self ! ZMQMessage(Seq(Frame(message)))
    } else {
      self ! PushMessage()
    }

    counter += 1
    if (counter >= 3000) {
      counter = 0
    }
  }
}
