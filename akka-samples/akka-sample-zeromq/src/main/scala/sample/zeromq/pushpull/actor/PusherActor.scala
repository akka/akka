package sample.zeromq.pushpull.actor

import akka.actor.{ Actor, ActorLogging }
import akka.event.Logging
import akka.util.ByteString
import akka.zeromq._
import sample.zeromq.Util

private case class PushMessage()

class PusherActor extends Actor with ActorLogging {
  log.info("Binding...")

  private val pusherActor = ZeroMQExtension(context.system).newPushSocket(
    Array(
      Bind("tcp://127.0.0.1:1234"),
      Listener(self)))

  val maxMessageSize = 100

  var counter = 0
  val modulo = 256

  private def pushMessage() = {
    if (counter % modulo == 0) {
      val message = Util.randomString(maxMessageSize)
      self ! ZMQMessage(ByteString(message))
    } else {
      self ! PushMessage()
    }

    counter = if (counter >= 2999) 0 else counter + 1
  }

  def receive = {
    case m: ZMQMessage  ⇒ pusherActor ! m; pushMessage()
    case p: PushMessage ⇒ pushMessage()
    case Binding        ⇒ pushMessage()
  }
}
